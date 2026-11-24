// Replays the ORIGINAL computeRiskScore() from the untouched terminal HTML against random inputs and
// writes the expected outputs for the Java ScoreParityTest. Usage: node gen-score-fixtures.js <original.html> <out.json>
const fs = require('fs');
const [,, htmlPath, outPath] = process.argv;
const html = fs.readFileSync(htmlPath, 'utf8');
const m = html.match(/function computeRiskScore\(\)\{[\s\S]*?\n\}\r?\n/);
if (!m) throw new Error('computeRiskScore not found');
const ASSET_DEFS = [{id:'gold'},{id:'nifty'},{id:'usEq'},{id:'usBonds'},{id:'btc'}];
const run = new Function('dataset', 'ASSET_DEFS', m[0] + '; return computeRiskScore();');
let seed = 12345; const rnd = () => (seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296;
const labels = ['Bullish','Bearish','Neutral',''];
const cases = [];
for (let i = 0; i < 4000; i++) {
  const bias = {};
  for (const a of ASSET_DEFS) {
    const b = labels[Math.floor(rnd()*4)];
    const r = rnd();
    const cf = r < 0.2 ? null : r < 0.3 ? [0,100,50,1,99][Math.floor(rnd()*5)] : r < 0.5 ? Math.round(rnd()*1000)/10 : Math.floor(rnd()*101);
    bias[a.id] = { b, cf };
  }
  const expected = run({ bias }, ASSET_DEFS);
  cases.push({ bias, expected });
}
fs.writeFileSync(outPath, JSON.stringify(cases));
console.log('cases', cases.length, 'nulls', cases.filter(c=>c.expected===null).length);
