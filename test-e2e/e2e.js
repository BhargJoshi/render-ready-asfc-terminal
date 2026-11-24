// Browser-level integration test: real patched HTML + real JS (jsdom) -> real Java backend -> mock Groq/newsdata.
// Usage: node e2e.js   (expects ../backend/target/classes and Jackson jars; see run-e2e.sh)
const http = require('http'), net = require('net'), path = require('path'), { spawn } = require('child_process');
const { JSDOM, ResourceLoader, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const JARS = (process.env.JACKSON_JARS || '/usr/share/java/jackson-core.jar:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-annotations.jar');
const results = []; let failed = 0;
function check(name, cond, detail) { results.push({ name, ok: !!cond }); if (!cond) { failed++; console.log('  FAIL ', name, detail !== undefined ? '-> ' + detail : ''); } else console.log('  ok   ', name); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function until(fn, ms = 8000) { const t = Date.now(); while (Date.now() - t < ms) { if (await fn()) return true; await sleep(40); } return false; }
const freePort = () => new Promise(res => { const s = net.createServer(); s.listen(0, () => { const p = s.address().port; s.close(() => res(p)); }); });

// ------------------------------------------------------------ mock upstream (Groq + newsdata)
const SOURCE = 'The Fed held rates at 5.50% on Wednesday. Headline CPI cooled to 3.2% from 3.4% in September. Gold rallied as the dollar softened and yields fell. The 10-year Treasury yield fell to 4.10%. Analysts expect the next catalyst to be the payrolls report on Friday. Bond markets rallied and equity volatility eased after the decision.';
const MACRO_FULL = { meta:{source:'Reuters',session:'US Close',headline:'Gold rallies as yields fall'},
  macro:{ fed:{cur:'5.50%',prev:'5.50%',cons:'5.50%',d:'flat',note:'Held at cycle high',src:'The Fed held rates at 5.50% on Wednesday.'},
          cpiH:{cur:'3.2%',prev:'3.4%',cons:'',d:'down',note:'Cooled again',src:'Headline CPI cooled to 3.2% from 3.4% in September.'},
          y10:{cur:'4.10%',prev:'',cons:'',d:'down',note:'Yields fell',src:'The 10-year Treasury yield fell to 4.10%.'} },
  bias:{ gold:{b:'Bullish',cf:80,pd:'Falling yields',br:'Hawkish Fed',nc:'Payrolls Friday',src:'Gold rallied as the dollar softened and yields fell.'},
         usBonds:{b:'Bullish',cf:70,pd:'Yields falling',br:'Sticky inflation',nc:'',src:'The 10-year Treasury yield fell to 4.10%.'} },
  matrix:{ gold:{bd:'Softer dollar',brd:'Hawkish Fed',cd:'Lower yields',b:'Bullish',cf:80,src:'Gold rallied as the dollar softened and yields fell.'},
           usBonds:{bd:'Falling yields',brd:'Sticky inflation',cd:'Yields',b:'Bullish',cf:70,src:'The 10-year Treasury yield fell to 4.10%.'} },
  pos:{ gold:{v:'Bullish',tone:'bullish',src:'Gold rallied as the dollar softened and yields fell.'} } };
const EVENTS_FULL = { events:[{cat:'Fed',t:'',dt:'Wednesday',h:'Fed holds rates at 5.50%',w:'Signals patience',aa:'US Bonds, Gold',imp:'High',src:'The Fed held rates at 5.50% on Wednesday.'}],
  narratives:[{n:'Disinflation',tone:'bullish',s:70,aa:'Gold',src:'Headline CPI cooled to 3.2% from 3.4% in September.'}],
  watchlist:[{t:'',dt:'Friday',e:'Payrolls report',f:'',imp:'High',aa:'US Equities',src:'Analysts expect the next catalyst to be the payrolls report on Friday.'}],
  today:[], summary:{bullets:['Disinflation and falling yields support gold and duration.','The Fed is on hold, leaving data as the swing factor.']} };
const MACRO_PARTIAL = { meta:{}, macro:{ vix:{cur:'18.5',prev:'',cons:'',d:'down',note:'Vol eased',src:'equity volatility eased'} }, bias:{}, matrix:{}, pos:{} };
const EVENTS_EMPTY = { events:[], narratives:[], watchlist:[], today:[], summary:{bullets:[]} };

const mock = { macro: MACRO_FULL, events: EVENTS_FULL, mode: 'ok', lastAuth: null, groqCalls: 0, newsQuery: null };
function startMock(port) {
  return new Promise(res => {
    const srv = http.createServer((req, rsp) => {
      let body = ''; req.on('data', c => body += c);
      req.on('end', () => {
        if (req.url.startsWith('/groq')) {
          mock.lastAuth = req.headers['authorization']; mock.groqCalls++;
          const isMacro = body.includes('HALF 1 of 2'), isEvents = body.includes('HALF 2 of 2');
          const fail = mock.mode === 'rejectAll' || (mock.mode === 'failEvents' && isEvents);
          if (fail) { rsp.writeHead(401, {'Content-Type':'application/json'}); return rsp.end(JSON.stringify({error:{message:'Invalid API Key',code:'invalid_api_key'}})); }
          const payload = isMacro ? mock.macro : isEvents ? mock.events : null;
          const content = payload ? JSON.stringify(payload) : 'Gold is bullish because yields fell.';
          rsp.writeHead(200, {'Content-Type':'application/json','x-ratelimit-limit-tokens':'60000'});
          return rsp.end(JSON.stringify({ choices:[{ message:{ content }, finish_reason:'stop' }], usage:{} }));
        }
        if (req.url.startsWith('/news/latest')) {
          mock.newsQuery = req.url;
          rsp.writeHead(200, {'Content-Type':'application/json'});
          return rsp.end(JSON.stringify({ status:'success', totalResults:1, results:[{ article_id:'a1', title:'Gold rises as Fed rate cut bets grow and Treasury yields fall',
            link:'https://example.com/a1', description:'Gold prices climbed as traders raised bets on Fed rate cuts while Treasury yields and the dollar fell.',
            content:'Gold prices climbed as traders raised bets on Fed rate cuts while Treasury yields and the dollar fell.', pubDate:'2026-10-17 08:30:00', source_name:'Reuters', source_id:'reuters', category:['business'], language:'english' }] }));
        }
        rsp.writeHead(404); rsp.end();
      });
    });
    srv.listen(port, '127.0.0.1', () => res(srv));
  });
}

// ------------------------------------------------------------ Java backend process
function startBackend(port, env) {
  const child = spawn('java', ['-cp', `${ROOT}/backend/target/classes:${JARS}`, 'com.ashford.terminal.App'], {
    env: Object.assign({}, process.env, { PORT: String(port), STATIC_DIR: `${ROOT}/frontend` }, env), stdio: ['ignore', 'pipe', 'pipe'] });
  let out = ''; child.stdout.on('data', d => out += d); child.stderr.on('data', d => out += d);
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('backend did not start: ' + out)), 15000);
    const iv = setInterval(() => { if (out.includes('listening on')) { clearInterval(iv); clearTimeout(t); res({ child, log: () => out }); } }, 50);
    child.on('exit', c => { if (!out.includes('listening on')) { clearTimeout(t); rej(new Error('backend exited ' + c + ': ' + out)); } });
  });
}

// ------------------------------------------------------------ browser (jsdom) page
async function openPage(port, seedStorage) {
  const store = new Map(); if (seedStorage) store.set('terminal_dataset_v1', JSON.stringify(seedStorage));
  const errors = [];
  const vc = new VirtualConsole(); vc.on('jsdomError', e => errors.push(String(e.message || e)));
  class Loader extends ResourceLoader { fetch(url, o) { return url.startsWith(`http://localhost:${port}`) ? super.fetch(url, o) : Promise.resolve(Buffer.from('')); } }
  const dom = await JSDOM.fromURL(`http://localhost:${port}/`, { runScripts: 'dangerously', resources: new Loader(), pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(win) {
      win.storage = { get: async k => store.has(k) ? { value: store.get(k) } : null, set: async (k, v) => { store.set(k, v); } };
      win.fetch = async (input, init = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url, win.location.href).href;
        const ac = new AbortController();
        if (init.signal) { if (init.signal.aborted) ac.abort(); else init.signal.addEventListener('abort', () => ac.abort()); }
        const headers = Object.assign({}, init.headers); if ((init.method || 'GET') !== 'GET') headers['Origin'] = win.location.origin;  // browsers send Origin on POST
        return fetch(url, { method: init.method || 'GET', headers, body: init.body, signal: ac.signal });
      };
    } });
  const win = dom.window;
  await until(() => win.eval('typeof handleNewIntelligence') === 'function' && win.document.querySelector('#statusPills').textContent.length > 0);
  await until(() => win.eval('backendHealth') !== null, 6000);   // init() finished its health check, so banner assertions are meaningful
  await sleep(100);
  const $ = s => win.document.querySelector(s);
  return { win, $, errors, store, text: s => ($(s) ? $(s).textContent : ''), ev: code => win.eval(code), banner: () => $('#errorBanner').classList.contains('hidden') ? '' : $('#errorText').textContent };
}

(async () => {
  const mockPort = await freePort(), portA = await freePort(), portB = await freePort();
  const mockSrv = await startMock(mockPort);
  const baseEnv = { GROQ_API_URL: `http://127.0.0.1:${mockPort}/groq`, NEWSDATA_BASE_URL: `http://127.0.0.1:${mockPort}/news`, GROQ_API_KEY: 'server-side-groq-secret', NEWSDATA_API_KEY: 'server-side-news-secret' };
  let backend = await startBackend(portA, baseEnv);
  const procs = [backend];
  try {
    console.log('\n[1] page served by the backend, no secrets in the client, healthy start');
    let page = await openPage(portA);
    const html = await (await fetch(`http://localhost:${portA}/`)).text();
    check('served page contains no key material', !/gsk_[A-Za-z0-9]{20,}|pub_[A-Za-z0-9]{20,}|server-side-groq-secret|server-side-news-secret/.test(html));
    check('no startup warning banner when backend + Groq are configured', page.banner() === '', page.banner());
    check('no JS errors on load', page.errors.length === 0, page.errors.join(' | '));
    check('empty dashboard shows Awaiting Data', page.text('#statusPills').includes('Awaiting Data'));

    console.log('\n[2] successful AI extraction -> Java validation/reconcile/score -> dashboard');
    let r = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'e2e paste' });
    check('handleNewIntelligence succeeded via Groq', r.success && r.engine === 'groq', JSON.stringify(r));
    check('Groq key was injected by the backend (browser never sent one)', mock.lastAuth === 'Bearer server-side-groq-secret', mock.lastAuth);
    check('bias card rendered', page.text('#biasStrip').includes('Bullish'));
    check('macro tile rendered', page.text('#macroStrip').includes('5.50%'));
    check('gauge shows Java score +100', page.text('#gaugeWrap').includes('+100'), page.text('#gaugeWrap'));
    check('engine pill + validator/integrity pill shown', page.text('#statusPills').includes('Engine: Groq') && page.text('#statusPills').includes('Integrity:'), page.text('#statusPills'));
    check('provenance recorded as UPDATED', page.ev("integrity.fields['bias.gold'].status") === 'UPDATED' && page.ev("integrity.fields['macro.vix'].status") === 'MISSING');
    check('unknown values stay empty (no fabricated zero/neutral)', page.ev("dataset.macro.vix.cur") === '' && page.ev("dataset.bias.btc.b") === '' && page.ev("dataset.bias.btc.cf") === null);
    check('score came from Java', page.ev("serverScore && serverScore.ref === dataset") === true);
    check('persisted with provenance', JSON.parse(page.store.get('terminal_dataset_v1')).integrity.fields['bias.gold'].status === 'UPDATED');

    console.log('\n[3] partial follow-up: unmentioned values are labelled carried forward, never "new"');
    mock.macro = MACRO_PARTIAL; mock.events = EVENTS_EMPTY;
    r = await page.win.handleNewIntelligence(SOURCE + ' VIX fell to 18.5 as equity volatility eased.', { sourceLabel: 'e2e partial' });
    check('second update succeeded', r.success, JSON.stringify(r));
    check('new value UPDATED, old value CARRIED_FORWARD', page.ev("integrity.fields['macro.vix'].status") === 'UPDATED' && page.ev("integrity.fields['macro.fed'].status") === 'CARRIED_FORWARD');
    check('old value preserved', page.ev("dataset.macro.fed.cur") === '5.50%' && page.ev("dataset.bias.gold.b") === 'Bullish');
    check('carried badge visible on tile and card', page.$('#macroStrip .integrity-badge') !== null && page.text('#biasStrip').includes('Carried'));
    page.win.openDetail('macro', 'fed');
    check('detail modal explains provenance', page.text('#detailBody').includes('Carried forward') && page.text('#detailBody').includes('NOT been re-verified'), page.text('#detailBody').slice(0, 300));
    page.win.openDetail('risk-gauge', 'risk');
    check('gauge detail discloses non-fresh inputs', page.text('#detailBody').includes('Not refreshed by the latest update'));

    console.log('\n[4] one extraction half fails (Groq rejects HALF 2): partial failure handled');
    mock.macro = MACRO_FULL; mock.events = EVENTS_FULL; mock.mode = 'failEvents';
    r = await page.win.handleNewIntelligence(SOURCE + ' Again.', { sourceLabel: 'e2e half-fail' });
    check('update still succeeded (macro half applied)', r.success && r.engine === 'groq', JSON.stringify(r));
    check('events section marked EXTRACTION_FAILED but data retained', page.ev("integrity.sections.events.extraction") === 'failed' && page.ev("integrity.fields['events'].status") === 'EXTRACTION_FAILED' && page.ev("dataset.events.length") === 1);
    check('banner explains the partial failure', /half failed|events/i.test(page.banner()), page.banner());

    console.log('\n[5] Groq unavailable (rejects everything): local parser + Java validation');
    mock.mode = 'rejectAll';
    r = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'e2e groq-down' });
    check('falls back to local parser and still succeeds', r.success && r.engine === 'local', JSON.stringify(r));
    check('Java validated the local result', page.ev("validatorMode") === 'java' && page.ev("integrity.engine") === 'local');
    check('banner says local backup parser was used', page.banner().includes('local backup parser'), page.banner());

    console.log('\n[6] concurrency: duplicate/overlapping work cannot corrupt state');
    mock.mode = 'ok'; mock.macro = MACRO_FULL; mock.events = EVENTS_FULL;
    const p1 = page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'first' });
    const p2 = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'second (overlapping)' });
    check('overlapping update is rejected, not run twice', p2.success === false && /Already processing/.test(p2.reason), JSON.stringify(p2));
    await page.win.reevaluateLoaded(Date.now());   // must be a no-op while parsing
    const r1 = await p1;
    check('first update completed normally', r1.success, JSON.stringify(r1));
    const verBefore = page.ev('datasetVersion');
    check('re-evaluation does not clobber after update', page.ev("serverScore.ref === dataset"));
    await page.win.reevaluateLoaded(Date.now());
    check('re-evaluation applies when idle and keeps values', page.ev('datasetVersion') === verBefore && page.ev("dataset.macro.fed.cur") === '5.50%');

    console.log('\n[7] live news search + assistant chat go through the backend');
    const arts = await page.win.fetchLiveNews('gold');
    check('news relayed and mapped', Array.isArray(arts) && arts.length === 1 && /Gold rises/.test(arts[0].title), JSON.stringify(arts).slice(0, 200));
    check('backend added key/category server-side', /apikey=server-side-news-secret/.test(mock.newsQuery) && /category=business/.test(mock.newsQuery));
    const reply = await page.win.callGroq([{ role: 'user', content: 'hi' }], { temperature: 0.4, maxOutputTokens: 256, includeReasoning: false });
    check('assistant call relayed', typeof reply === 'string' && reply.includes('Gold is bullish'), reply);
    page.win.document.querySelector('#chatInput').value = 'why gold?';
    await page.win.sendChatMessage('Why is gold bullish?');
    check('chat transcript contains the reply', page.ev("chatHistory[chatHistory.length-1].text").includes('Gold is bullish'));
    check('assistant context includes data-freshness notes', page.ev("buildDatasetDigest()").length > 100);

    console.log('\n[8] article add: older source must not overwrite newer data');
    page.ev("lastUpdatedAt = new Date()");
    mock.macro = JSON.parse(JSON.stringify(MACRO_FULL)); mock.macro.bias.gold = { b:'Bearish', cf:90, pd:'x', br:'y', nc:'', src:'Gold rallied as the dollar softened and yields fell.' };
    r = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'old article', sourceAsOf: '2020-01-01T00:00:00Z', keepInput: true });
    check('older source did not overwrite gold bias', page.ev("dataset.bias.gold.b") === 'Bullish', page.ev("dataset.bias.gold.b"));
    check('stale/older provenance visible', ['STALE', 'CARRIED_FORWARD'].includes(page.ev("integrity.fields['bias.gold'].status")));
    mock.macro = MACRO_FULL;

    console.log('\n[9] sample/demo data is never mixed into a live dataset');
    page.$('#sampleBtn').click();
    check('demo loaded, no integrity/badges', page.ev('isDemo') && page.ev('integrity') === null && page.win.document.querySelector('.integrity-badge') === null);
    check('demo gauge uses the original local formula', page.ev('computeRiskScore() === computeRiskScoreLocal()'));
    mock.macro = MACRO_PARTIAL; mock.events = EVENTS_EMPTY;
    r = await page.win.handleNewIntelligence(SOURCE + ' VIX fell to 18.5.', { sourceLabel: 'after demo' });
    check('demo values were NOT carried into the live dataset', page.ev("dataset.macro.fed.cur") === '' && page.ev("dataset.bias.gold.b") === '' && page.ev("integrity.fields['macro.fed'].status") === 'MISSING', page.ev("dataset.macro.fed.cur"));
    mock.macro = MACRO_FULL; mock.events = EVENTS_FULL;

    console.log('\n[10] reload with data persisted BEFORE provenance existed');
    const legacy = JSON.parse(page.store.get('terminal_dataset_v1')); legacy.integrity = null; legacy.demo = false; legacy.savedAt = Date.now() - 3 * 3600 * 1000;
    r = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'seed' }); legacy.ds = page.ev('dataset');
    const page2 = await openPage(portA, legacy);
    await until(() => page2.ev("integrity !== null"), 5000);
    check('legacy values are labelled carried-forward, never new', page2.ev("integrity.fields['macro.fed'].status") === 'CARRIED_FORWARD' && page2.ev("integrity.fields['macro.fed'].legacy") === true);
    check('age reflects the save time (~3h)', Math.abs(page2.ev("integrity.fields['macro.fed'].ageMinutes") - 180) <= 2, page2.ev("integrity.fields['macro.fed'].ageMinutes"));
    check('badge rendered after reload', page2.text('#macroStrip').includes('Carried'));

    console.log('\n[11] file upload (.txt) uses the same pipeline');
    const up = new page.win.File([SOURCE], 'note.txt', { type: 'text/plain' });
    await page.win.handleFileUpload(up);
    check('upload processed (source label recorded)', page.ev("dataset.macro.fed.cur") === '5.50%' && page.ev("lastEngine") === 'groq');

    console.log('\n[12] Java backend becomes unavailable mid-session');
    backend.child.kill('SIGKILL'); await sleep(400);
    r = await page.win.handleNewIntelligence(SOURCE, { sourceLabel: 'backend-down' });
    check('update still succeeds via local parser + browser validation', r.success && r.engine === 'local', JSON.stringify(r));
    check('labelled as browser fallback, provenance cleared (not faked)', page.ev("validatorMode") === 'browser-fallback' && page.ev("integrity") === null);
    check('banner says Java validation was unavailable', page.banner().includes('Java validation service was unavailable'), page.banner());
    check('fallback pill visible and gauge still renders', page.text('#statusPills').includes('Browser Fallback') && /[+-]?\d+/.test(page.text('#gaugeWrap')));
    page.win.openDetail('integrity', 'all');
    check('integrity detail explains the unverified state', page.text('#detailBody').includes('built-in browser logic'));
    const bad = await page.win.fetchLiveNews('gold').then(() => 'ok', e => e.message);
    check('news search reports backend unreachable cleanly', /Java backend/.test(bad), bad);

    console.log('\n[13] backend up but GROQ_API_KEY not configured');
    const b2 = await startBackend(portB, Object.assign({}, baseEnv, { GROQ_API_KEY: '' })); procs.push(b2);
    const page3 = await openPage(portB);
    check('startup banner explains missing key', page3.banner().includes('GROQ_API_KEY'), page3.banner());
    r = await page3.win.handleNewIntelligence(SOURCE, { sourceLabel: 'no-key' });
    check('still works through local parser + Java', r.success && r.engine === 'local' && page3.ev("validatorMode") === 'java', JSON.stringify(r));
    check('failure reason names the missing key', /GROQ_API_KEY/.test(page3.banner()), page3.banner());
    page3.win.openChat();
    check('assistant heads-up mentions missing key', /GROQ_API_KEY/.test(page3.ev("chatHistory.map(m=>m.text).join(' ')")));

    console.log('\n[14] no unexpected JS errors during the whole run');
    check('no jsdom script errors', page.errors.length === 0 && page2.errors.length === 0 && page3.errors.length === 0, [...page.errors, ...page2.errors, ...page3.errors].join(' | '));
  } catch (e) {
    failed++; console.log('  CRASH', e && e.stack || e);
  } finally {
    procs.forEach(p => { try { p.child.kill('SIGKILL'); } catch (_) {} });
    mockSrv.close();
  }
  console.log(`\n${results.length - failed} / ${results.length} checks passed`);
  process.exit(failed ? 1 : 0);
})();
