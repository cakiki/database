const fs = require('fs-extra');
const prettyBytes = require('pretty-bytes');
const moment = require('moment');

const sourceDir = process.argv[2];
const indexFile = 'index.html';
const indexTpl = indexFile + '.tpl';
const tableTpl = 'table.html.tpl';
const styleFile = 'style.css';
const listFile = 'list.txt';

const clockSince = moment('2017-04');

function numberFormat(n) {
  return new Intl.NumberFormat().format(n);
}

function fileInfo(gameCounts, variant, n) {
  const path = sourceDir + '/' + variant + '/' + n;
  return fs.stat(path).then(s => {
    const dateStr = n.replace(/.+(\d{4}-\d{2})\.pgn\.bz2/, '$1');
    const m = moment(dateStr);
    const shortName = n.replace(/.+(\d{4}-\d{2}.+)$/, '$1');
    const hasClock = m.unix() >= clockSince.unix();
    return {
      name: n,
      shortName: shortName,
      path: path,
      size: s.size,
      date: m,
      clock: hasClock,
      games: parseInt(gameCounts[n]) || 0
    };
  });
}

function getGameCounts(variant) {
  return fs.readFile(sourceDir + '/' + variant + '/counts.txt', { encoding: 'utf8' }).then(c => {
    var gameCounts = {};
    c.split('\n').map(l => l.trim()).forEach(line => {
      if (line !== '') gameCounts[line.split(' ')[0]] = line.split(' ')[1];
    });
    return gameCounts;
  });
}

function getFiles(variant) {
  return function(gameCounts) {
    return fs.readdir(sourceDir + '/' + variant).then(items => {
      return Promise.all(
        items.filter(n => n.includes('.pgn.bz2')).map(n => fileInfo(gameCounts, variant, n))
      );
    }).then(items => items.sort((a, b) => b.date.unix() - a.date.unix()));
  }
}

function renderTable(files, variant) {
  return files.map(f => {
    return `<tr>
    <td>${f.date.format('MMMM YYYY')}</td>
    <td class="right">${prettyBytes(f.size)}</td>
    <td class="right">${f.games ? numberFormat(f.games) : '?'}</td>
    <td class="center">${f.clock ? '✔' : ''}</td>
    <td><a href="${variant}/${f.name}">${f.shortName}</a></td>
    </tr>`;
  }).join('\n');
}

function renderTotal(files) {
  return `<tr class="total">
  <td>Total: ${files.length} files</td>
  <td class="right">${prettyBytes(files.map(f => f.size).reduce((a, b) => a + b))}</td>
  <td class="right">${numberFormat(files.map(f => f.games).reduce((a, b) => a + b))}</td>
  <td></td>
  <td></td>
  </tr>`;
}

function renderList(files, variant) {
  return files.map(f => {
    return `https://database.lichess.org/${variant}/${f.name}`;
  }).join('\n');
}

function processVariantAndReturnTable(variant, template) {
    return getGameCounts(variant).then(getFiles(variant)).then(files => {
        return fs.writeFile(sourceDir + '/' + variant + '/' + listFile, renderList(files, variant)).then(_ => {
          return template
            .replace(/<!-- nbGames -->/, numberFormat(files.map(f => f.games).reduce((a, b) => a + b)))
            .replace(/<!-- files -->/, renderTable(files, variant))
            .replace(/<!-- total -->/, renderTotal(files))
            .replace(/<!-- variant -->/g, variant);
        });
    });
}

function replaceVariant(variant, tableTemplate) {
    return function(fullTemplate) {
        return processVariantAndReturnTable(variant, tableTemplate).then(tbl => {
            return fullTemplate.replace('<!-- table-' + variant + ' -->', tbl);
        });
    };
}

process.on('unhandledRejection', r => console.log(r));

Promise.all([
  fs.readFile(indexTpl, { encoding: 'utf8' }),
  fs.readFile(tableTpl, { encoding: 'utf8' }),
  fs.readFile(styleFile, { encoding: 'utf8' })
]).then(arr => {
  const rv = function(variant) { return replaceVariant(variant, arr[1]); };
  return rv('standard')(arr[0])
    .then(rv('antichess'))
    .then(rv('atomic'))
    .then(rv('chess960'))
    .then(rv('crazyhouse'))
    .then(rv('horde'))
    .then(rv('kingOfTheHill'))
    .then(rv('racingKings'))
    .then(rv('threeCheck'))
    .then(rendered => {
        return fs.writeFile(sourceDir + '/' + indexFile, rendered.replace(/<!-- style -->/, arr[2]));
    });
});
