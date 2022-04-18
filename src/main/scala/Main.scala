package lichess

import akka.actor.ActorSystem
import akka.stream.*
import akka.stream.scaladsl.*
import akka.util.ByteString
import chess.format.pgn.Pgn
import chess.variant.{ Horde, Standard, Variant }
import java.nio.file.Paths
import lila.analyse.Analysis
import lila.analyse.Analysis.given
import lila.db.dsl.*
import lila.db.dsl.given
import lila.game.BSONHandlers.*
import lila.game.BSONHandlers.given
import lila.game.{ Game, PgnDump, Source as S }
import org.joda.time.DateTime
import reactivemongo.akkastream.{ cursorProducer, State }
import reactivemongo.api.*
import reactivemongo.api.bson.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@main def buildGameDb(month: String, others: String*) =

  val path = others.lift(1).getOrElse("out/lichess_db_%.pgn").replace("%", month)

  val variant = Variant
    .apply(others.lift(2).getOrElse("standard"))
    .getOrElse(throw new RuntimeException("Invalid variant."))

  val fromWithoutAdjustments =
    new DateTime(month).withDayOfMonth(1).withTimeAtStartOfDay()
  val to = fromWithoutAdjustments plusMonths 1

  val hordeStartDate = new DateTime(2015, 4, 11, 10, 0)
  val from =
    if (
      variant == Horde && hordeStartDate
        .compareTo(fromWithoutAdjustments) > 0
    ) hordeStartDate
    else fromWithoutAdjustments

  if (from.compareTo(to) > 0)
    System.out.println("Too early for Horde games. Exiting.");
    System.exit(0);

  println(s"Export $from to $path")

  given system: ActorSystem = ActorSystem()
  given materializer: Materializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withInputBuffer(
        initialSize = 32,
        maxSize = 32
      )
  )

  lichess.DB.get foreach { case (db, close) =>
    val sources = List(S.Lobby, S.Friend, S.Tournament, S.Pool)

    val query = BSONDocument(
      "ca" -> BSONDocument("$gte" -> from, "$lt" -> to),
      "ra" -> true,
      "v"  -> BSONDocument("$exists" -> variant.exotic)
    )

    val gameSource = db.gameColl
      .find(query)
      .sort(BSONDocument("ca" -> 1))
      // .cursor[Game.WithInitialFen]()
      .cursor[Game.WithInitialFen](readPreference = ReadPreference.secondary)
      .documentSource(
        maxDocs = Int.MaxValue,
        err = Cursor.ContOnError((_, e) => println(e.getMessage))
      )

    val tickSource =
      Source.tick(Reporter.freq, Reporter.freq, None)

    def checkLegality(g: Game): Future[(Game, Boolean)] = Future {
      g -> chess.Replay
        .boards(g.pgnMoves, None, g.variant)
        .fold(
          err => {
            println(s"Replay error ${g.id} ${err.toString.take(60)}")
            false
          },
          boards => {
            if (boards.size == g.pgnMoves.size + 1) true
            else {
              println(
                s"Replay error ${g.id} boards.size=${boards.size}, moves.size=${g.pgnMoves.size}"
              )
              false
            }
          }
        )
    }

    type Analysed = (Game.WithInitialFen, Option[Analysis])
    def withAnalysis(gs: Seq[Game.WithInitialFen]): Future[Seq[Analysed]] =
      db.analysisColl
        .find(
          BSONDocument(
            "_id" -> BSONDocument(
              "$in" -> gs.filter(_.game.metadata.analysed).map(_.game.id)
            )
          )
        )
        .cursor[Analysis](readPreference = ReadPreference.secondary)
        .collect[List](Int.MaxValue, Cursor.ContOnError[List[Analysis]]()) map { as =>
        gs.map { g =>
          g -> as.find(_.id == g.game.id)
        }
      }

    type WithUsers = (Analysed, Users)
    def withUsers(as: Seq[Analysed]): Future[Seq[WithUsers]] =
      db.users(as.map(_._1.game)).map { users =>
        as zip users
      }

    def toPgn(ws: Seq[WithUsers]): Future[Seq[Pgn]] =
      Future(ws map { case ((g, analysis), users) =>
        val pgn = PgnDump(g.game, users, g.fen)
        lila.analyse.Annotator(
          pgn,
          analysis,
          g.game.winnerColor,
          g.game.status,
          g.game.clock
        )
      })

    def pgnSink: Sink[Seq[Pgn], Future[IOResult]] =
      Flow[Seq[Pgn]]
        .map { pgns =>
          // merge analysis & eval comments
          // 1. e4 { [%eval 0.17] } { [%clk 0:00:30] }
          // 1. e4 { [%eval 0.17] [%clk 0:00:30] }
          val str =
            pgns.map(_.toString).mkString("\n\n").replace("] } { [", "] [")
          ByteString(s"$str\n\n")
        }
        .toMat(FileIO.toPath(Paths.get(path)))(Keep.right)

    gameSource
      .buffer(10000, OverflowStrategy.backpressure)
      .filter(_.game.variant == variant)
      .map(g => Some(g))
      .merge(tickSource, eagerComplete = true)
      .via(Reporter)
      // .mapAsyncUnordered(16)(checkLegality)
      // .filter(_._2).map(_._1)
      .grouped(100)
      .mapAsyncUnordered(16)(withAnalysis)
      .mapAsyncUnordered(16)(withUsers)
      .mapAsyncUnordered(16)(toPgn)
      .runWith(pgnSink) andThen { case state =>
      close()
      system.terminate()
    }
  }
