#!/usr/bin/env python3
"""Applies the Java-backend integration to the original terminal HTML.
Every edit asserts its anchor matches exactly once, so a changed source fails loudly instead of mis-patching.
Usage: patch_frontend.py <original.html> <output.html>"""
import sys
src, dst = sys.argv[1], sys.argv[2]
raw = open(src, encoding='utf-8', newline='').read()
crlf = '\r\n' in raw
s = raw.replace('\r\n', '\n')

def rep(old, new, count=1):
    global s
    n = s.count(old)
    assert n == count, f'anchor matched {n}x (expected {count}): {old[:90]!r}'
    s = s.replace(old, new)

# ---------------------------------------------------------------- CSS
rep('</style>', r'''.integrity-badge{ display:inline-block; margin-left:6px; padding:0 5px; border:1px solid currentColor; border-radius:3px; font-size:9px; font-weight:600; letter-spacing:.06em; text-transform:uppercase; line-height:14px; vertical-align:middle; color:var(--warn); opacity:.9; white-space:nowrap; }
.integrity-badge[data-status="EXTRACTION_FAILED"], .integrity-badge[data-status="REJECTED_INVALID"]{ color:var(--bear); }
.integrity-note{ font-size:11px; color:var(--warn); margin-left:6px; }
.pill[data-detail-type]{ cursor:pointer; }
</style>''')

# ---------------------------------------------------------------- CONFIG: secrets and provider URLs leave the browser
rep('const CONFIG = {\n', r'''/* Java backend (relays Groq / newsdata.io with server-side keys, validates, reconciles, scores).
   Served by the backend itself => same origin => BACKEND_URL is ''. Opened from file:// => defaults to localhost:8080.
   Override with window.ASHFORD_BACKEND_URL before this script runs. */
const BACKEND_URL = String(window.ASHFORD_BACKEND_URL || (location.protocol === 'file:' ? 'http://localhost:8080' : '')).replace(/\/$/, '');
const CONFIG = {
''')
rep("  NEWS_API_KEY:   'PASTE_YOUR_NEWSDATA_KEY_HERE',\n  NEWS_API_BASE:  'https://newsdata.io/api/1',\n",
    "  // API keys and provider URLs now live ONLY in the Java backend (env vars GROQ_API_KEY / NEWSDATA_API_KEY).\n  BACKEND_TIMEOUT_MS: 15000,\n")
rep("  GROQ_API_KEY:   'PASTE_YOUR_GROQ_KEY_HERE',\n", "")
rep("  GROQ_BASE:      'https://api.groq.com/openai/v1/chat/completions',\n",
    "  GROQ_BASE:      BACKEND_URL + '/api/groq/chat', // relayed by the Java backend, which adds the key\n")

# ---------------------------------------------------------------- STATE
rep('let groqRateLimitInfo = {', r'''let integrity = null;          // per-field provenance from the Java backend; null = demo / unavailable / browser-fallback update
let lastFlags = [];            // consistency flags from the last Java run (never alter the data, only annotate it)
let lastIssues = [];           // validation notes from the last Java run
let validatorMode = null;      // 'java' | 'browser-fallback' | null
let serverScore = null;        // { ref: dataset object it belongs to, value, detail }
let datasetVersion = 0;        // bumps whenever the dataset is replaced; async re-evaluation is discarded if it changed
let lastExtractionStatus = null; // set by summarizeWithGroq(): which half succeeded / failed
let backendHealth = null;      // result of GET /api/health, or {reachable:false}
let groqRateLimitInfo = {''')

# ---------------------------------------------------------------- backend helpers (after assetLabel)
rep("function assetLabel(id){ const a = ASSET_DEFS.find(x=>x.id===id); return a? a.label : id; }\n", r'''function assetLabel(id){ const a = ASSET_DEFS.find(x=>x.id===id); return a? a.label : id; }

/* ============================================================
   JAVA BACKEND CLIENT + INTEGRITY HELPERS
   ============================================================ */
async function backendFetch(path, init, timeoutMs){
  const c = new AbortController();
  const t = setTimeout(()=>c.abort(), timeoutMs || CONFIG.BACKEND_TIMEOUT_MS);
  try{ return await fetch(BACKEND_URL + path, Object.assign({ signal:c.signal }, init)); }
  finally{ clearTimeout(t); }
}
async function backendPost(path, body, timeoutMs){
  const resp = await backendFetch(path, { method:'POST', headers:{ 'Content-Type':'application/json' }, body:JSON.stringify(body) }, timeoutMs);
  if(!resp.ok) throw new Error(`Backend ${path} returned ${resp.status}`);
  return resp.json();
}
function newRunId(){ return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : (Date.now() + '-' + Math.random().toString(16).slice(2)); }
function isValidProcessResponse(r){
  const d = r && r.dataset;
  return !!(d && d.meta && d.macro && d.bias && d.matrix && Array.isArray(d.pos) && Array.isArray(d.events) && Array.isArray(d.narratives)
    && Array.isArray(d.watchlist) && Array.isArray(d.today) && d.summary && Array.isArray(d.summary.bullets)
    && r.integrity && r.integrity.fields && typeof r.foundCount === 'number' && r.score);
}
function newestPublishedAt(arts){
  let best = null;
  arts.forEach(a=>{ const t = Date.parse(a.publishedAt || ''); if(!isNaN(t) && (best === null || t > best)) best = t; });
  return best === null ? null : new Date(best).toISOString();
}
const INTEGRITY_LABEL = { CARRIED_FORWARD:'Carried', STALE:'Stale', EXTRACTION_FAILED:'Not refreshed', REJECTED_INVALID:'Rejected update' };
const INTEGRITY_TEXT = {
  UPDATED:'New \u2014 taken from the latest update.',
  CARRIED_FORWARD:'Carried forward \u2014 not mentioned in the latest update, so an earlier value is shown. It has NOT been re-verified.',
  STALE:'Stale \u2014 older than the freshness threshold. Treat as historical, not current.',
  EXTRACTION_FAILED:'Not refreshed \u2014 the extraction step for this section failed, so any value shown is an earlier one.',
  REJECTED_INVALID:'Update rejected \u2014 the new value failed validation, so the earlier value was kept.',
  MISSING:'No value \u2014 not found in any source loaded so far.'
};
function integrityEntry(key){ return (integrity && integrity.fields && integrity.fields[key]) || null; }
function fmtAge(min){
  if(min == null) return 'age unknown';
  if(min < 1) return 'just now';
  if(min < 60) return min + ' min ago';
  if(min < 2880) return Math.round(min/60) + ' h ago';
  return Math.round(min/1440) + ' d ago';
}
function integrityTitle(e){
  const reasons = { not_mentioned:'not mentioned in the latest update', extraction_failed:'extraction failed', rejected_invalid:'new value failed validation',
    older_source:'newer data already loaded', thin_update:'update too thin to replace the earlier recap', aged:'aged past the freshness threshold', legacy_no_provenance:'saved before provenance tracking' };
  return (INTEGRITY_TEXT[e.status] || e.status) + ' Last updated ' + fmtAge(e.ageMinutes) + (e.reason && reasons[e.reason] ? ' (' + reasons[e.reason] + ')' : '') + '.';
}
function integrityBadge(key){
  if(isDemo) return '';
  const e = integrityEntry(key);
  if(!e || !e.hasValue || !INTEGRITY_LABEL[e.status]) return '';
  return `<span class="integrity-badge" data-status="${esc(e.status)}" title="${esc(integrityTitle(e))}">${INTEGRITY_LABEL[e.status]}</span>`;
}
function sectionNote(key){
  if(isDemo) return '';
  const e = integrityEntry(key);
  if(!e || !e.hasValue || !INTEGRITY_LABEL[e.status]) return '';
  return ` <span class="integrity-note" title="${esc(integrityTitle(e))}">\u00B7 ${INTEGRITY_LABEL[e.status].toLowerCase()}</span>`;
}
function issuePrefixesFor(key){
  if(key.startsWith('bias.')) return [key, 'matrix.' + key.slice(5)];
  if(['events','narratives','watchlist','today'].includes(key)) return [key + '['];
  if(key === 'summary') return ['summary'];
  return [key];
}
function integrityBlock(key){
  if(isDemo || !integrity) return '';
  const e = integrityEntry(key);
  if(!e) return '';
  let html = `<div class="detail-section-title">Data Integrity</div>`;
  html += detailRow('Status', `<span class="integrity-badge" data-status="${esc(e.status)}" style="margin-left:0">${esc(e.status.replace(/_/g,' '))}</span>`);
  html += `<div class="detail-explain">${esc(integrityTitle(e))}</div>`;
  if(e.updatedAt) html += detailRow('Last updated', esc(new Date(e.updatedAt).toLocaleString()) + ' (' + fmtAge(e.ageMinutes) + ')');
  if(e.asOf && e.asOf !== e.updatedAt) html += detailRow('Source time', esc(new Date(e.asOf).toLocaleString()));
  if(e.engine) html += detailRow('Extracted by', esc(e.engine === 'groq' ? 'Groq (AI)' : e.engine === 'local' ? 'Local fallback parser' : e.engine));
  if(e.legacy) html += detailRow('Provenance', 'Saved before provenance tracking \u2014 origin unknown');
  const pref = issuePrefixesFor(key);
  const notes = (lastIssues || []).filter(i=>(i.severity==='warn'||i.severity==='error') && pref.some(p=>i.path.startsWith(p))).slice(0, 6);
  if(notes.length) html += detailRow('Validation notes', notes.map(i=>esc(i.message)).join('<br>'));
  const flags = (lastFlags || []).filter(f=>(f.refs||[]).some(r=>r===key || (key.startsWith('bias.') && r==='matrix.'+key.slice(5))));
  html += flags.map(f=>flagHtml(f)).join('');
  return html;
}
function flagHtml(f){
  return `<div class="detail-section-title">Consistency flag${f.severity==='warn'?' (check)':''}</div><div class="detail-explain">${esc(f.message)}${f.assumption ? `<br><span style="opacity:.75">Assumption: ${esc(f.assumption)}</span>` : ''}</div>`;
}
function integritySummaryBlock(){
  if(isDemo) return `<div class="detail-explain">The sample dataset is demo data \u2014 no provenance is tracked for it.</div>`;
  if(!integrity){
    return `<div class="detail-explain">${validatorMode==='browser-fallback'
      ? 'The Java validation service was unavailable for the last update, so it was validated and merged by the built-in browser logic. Per-field provenance and consistency checks are not available for this dataset \u2014 older values may be mixed with new ones without labels.'
      : 'No provenance is available for this dataset (it was loaded before provenance tracking, or the Java backend is not running).'}</div>`;
  }
  const sm = integrity.summary || {};
  let html = detailRow('New (this update)', String(sm.UPDATED||0)) + detailRow('Carried forward', String(sm.CARRIED_FORWARD||0))
    + detailRow('Stale', String(sm.STALE||0)) + detailRow('Extraction failed', String(sm.EXTRACTION_FAILED||0))
    + detailRow('Rejected as invalid', String(sm.REJECTED_INVALID||0)) + detailRow('No value', String(sm.MISSING||0));
  const secs = integrity.sections || {};
  ['macro','events'].forEach(k=>{
    if(secs[k] && secs[k].extraction === 'failed')
      html += detailRow(k==='macro' ? 'Macro / bias / positioning' : 'Events / narratives / summary', 'Extraction failed' + (secs[k].error ? ': ' + esc(secs[k].error) : ''));
  });
  const warn = (lastIssues||[]).filter(i=>i.severity==='warn'||i.severity==='error').slice(0, 25);
  if(warn.length) html += `<div class="detail-section-title">Validation notes (${warn.length}${(lastIssues||[]).filter(i=>i.severity!=='info').length>25?'+':''})</div><div class="detail-explain">${warn.map(i=>esc(i.path + ': ' + i.message)).join('<br>')}</div>`;
  if((lastFlags||[]).length) html += `<div class="detail-section-title">Consistency flags (${lastFlags.length})</div><div class="detail-explain">`
    + lastFlags.map(f=>`${f.severity==='warn'?'\u26A0 ':'\u2022 '}${esc(f.message)}${f.assumption ? ` <span style="opacity:.7">(${esc(f.assumption)})</span>` : ''}`).join('<br><br>')
    + `<br><br><span style="opacity:.7">Flags annotate possible contradictions; they never change the AI-generated content.</span></div>`;
  return html;
}
function scoreDisclosureBlock(){
  const d = serverScore && serverScore.ref === dataset && serverScore.detail;
  let html = '';
  if(d && d.defaultedConfidence && d.defaultedConfidence.length)
    html += `<div class="detail-section-title">Assumption in this score</div><div class="detail-explain">No confidence was available for ${d.defaultedConfidence.map(assetLabel).join(', ')}; the score formula weights those at 50% (unchanged legacy behaviour).</div>`;
  const nonFresh = ASSET_DEFS.filter(a=>{ const e = integrityEntry('bias.'+a.id); return dataset.bias[a.id].b && e && e.status !== 'UPDATED'; });
  if(nonFresh.length && !isDemo) html += `<div class="detail-section-title">Not refreshed by the latest update</div><div class="detail-explain">${nonFresh.map(a=>esc(a.label)).join(', ')} \u2014 included in the score from earlier data.</div>`;
  html += `<div class="detail-explain" style="opacity:.75">Computed by ${serverScore && serverScore.ref === dataset ? 'the Java backend' : 'the browser (backend result unavailable)'} using the same formula either way.</div>`;
  return html;
}
function digestIntegrityNotes(){
  if(isDemo || !integrity) return validatorMode === 'browser-fallback' ? 'DATA FRESHNESS: unknown \u2014 the last update was merged without provenance tracking, so some values may be older than others.' : '';
  const out = [];
  Object.keys(integrity.fields || {}).forEach(k=>{
    const e = integrity.fields[k];
    if(e.hasValue && e.status !== 'UPDATED') out.push(`- ${k}: ${e.status.replace(/_/g,' ').toLowerCase()} (updated ${fmtAge(e.ageMinutes)})`);
    else if(!e.hasValue && (e.status === 'EXTRACTION_FAILED' || e.status === 'REJECTED_INVALID')) out.push(`- ${k}: no value (${e.status.replace(/_/g,' ').toLowerCase()})`);
  });
  let txt = out.length ? 'DATA FRESHNESS \u2014 these values are NOT from the latest update:\n' + out.slice(0, 30).join('\n') : '';
  if((lastFlags||[]).length) txt += (txt ? '\n' : '') + 'CONSISTENCY FLAGS (possible contradictions, unresolved):\n' + lastFlags.map(f=>`- ${f.message}`).join('\n');
  return txt;
}
''')

# ---------------------------------------------------------------- callGroq: via backend, no key in browser
rep("        headers: { 'Content-Type':'application/json', 'Authorization': `Bearer ${CONFIG.GROQ_API_KEY}` },\n",
    "        headers: { 'Content-Type':'application/json' },\n")
rep("throw new Error('Could not reach Groq from the browser -- check your connection.');",
    "throw new Error('Could not reach the Java backend (AI extraction is relayed through it) -- is it running?');")
rep("      if(resp.status === 401){\n", r'''      // backend-originated conditions (the relay reports these itself)
      if(resp.status === 503 && code === 'groq_not_configured') throw new Error(detail || 'GROQ_API_KEY is not set on the backend.');
      if(resp.status === 504 || code === 'upstream_timeout') throw new Error('Groq request timed out.');
      if(resp.status === 502 && code === 'upstream_unreachable') throw new Error(detail || 'The backend could not reach Groq.');
      if(resp.status === 403 && (code === 'origin_not_allowed' || code === 'host_not_allowed')) throw new Error(detail || 'The backend refused this page\u2019s origin (see ALLOWED_ORIGINS).');
      if(resp.status === 401){
''')
rep("-- check CONFIG.GROQ_API_KEY in the code and console.groq.com/keys for a valid one.",
    "-- check the GROQ_API_KEY environment variable of the Java backend and console.groq.com/keys for a valid one.")

# ---------------------------------------------------------------- summarizeWithGroq: report which half failed
rep("async function summarizeWithGroq(rawText, opts){\n  lastPartialWarning = null;\n",
    "async function summarizeWithGroq(rawText, opts){\n  lastPartialWarning = null;\n  lastExtractionStatus = null;\n")
rep("  }\n\n  return combined;\n}", r'''  }

  lastExtractionStatus = {
    macro: errors.macro ? 'failed' : 'ok',
    events: errors.events ? 'failed' : 'ok',
    errors: { macro: errors.macro ? errors.macro.message : '', events: errors.events ? errors.events.message : '' }
  };
  return combined;
}''')

# ---------------------------------------------------------------- news via backend
rep("  const category = encodeURIComponent(CONFIG.NEWS_CATEGORY);\n  const url = `${CONFIG.NEWS_API_BASE}/latest?apikey=${CONFIG.NEWS_API_KEY}&q=${q}&category=${category}&language=en&size=10`;\n",
    "  const url = `${BACKEND_URL}/api/news/search?q=${q}`; // backend adds the key, category, language and size\n")
rep("Could not reach newsdata.io from the browser", "Could not reach the Java backend (live news is relayed through it)")
rep("check CONFIG.NEWS_API_KEY and newsdata.io/dashboard", "check NEWSDATA_API_KEY on the Java backend and newsdata.io/dashboard")

# ---------------------------------------------------------------- selected articles carry their publication time
rep("    keepInput: true\n  });", "    keepInput: true,\n    sourceAsOf: newestPublishedAt(selected)\n  });")

# ---------------------------------------------------------------- assistant heads-up now reflects the backend, not a key in the page
rep("    if(!isLikelyValidGroqKeyFormat(CONFIG.GROQ_API_KEY)){\n",
    "    if(backendHealth && (backendHealth.reachable === false || backendHealth.groqConfigured === false)){\n")
import re
HEADS_UP = ("text: backendHealth.reachable === false ? 'Heads up: the Java backend isn\\u2019t reachable, so the assistant can\\u2019t answer right now. Start it (see the README) and reload.' : 'Heads up: the Java backend has no GROQ_API_KEY configured, so the assistant can\\u2019t answer. Set it and restart the backend.'")
s, n = re.subn(r"text:'Heads up: the configured Groq key.*?for a valid key\.'", lambda m: HEADS_UP, s, count=1, flags=re.S)
assert n == 1

# ---------------------------------------------------------------- digest + narratives: unknown stays unknown
rep("  const score = computeRiskScore();\n  lines.push(`Composite risk/positioning gauge:",
    "  const score = computeRiskScore();\n  const integNotes = digestIntegrityNotes(); if(integNotes) lines.push(integNotes);\n  lines.push(`Composite risk/positioning gauge:")
rep("strength ${n.s})", "strength ${n.s!=null?n.s:'unknown'})")
rep('style="width:${n.s}%"', 'style="width:${n.s!=null?n.s:0}%"')
rep("detailRow('Strength', n.s+'%')", "detailRow('Strength', n.s!=null? n.s+'%':'\u2014')")

# ---------------------------------------------------------------- risk score: Java is authoritative, legacy kept verbatim as fallback
rep("function computeRiskScore(){\n  if(!dataset) return null;\n  let sum=0, weight=0;",
    r'''function computeRiskScore(){
  if(!dataset) return null;
  // The Java backend's result is used only if it was computed for exactly this dataset object (datasets are replaced, never mutated).
  if(serverScore && serverScore.ref === dataset) return serverScore.value;
  return computeRiskScoreLocal();
}
/* Original formula, unchanged. Used for the demo dataset, before the backend has answered, and whenever it is unavailable.
   The Java RiskScoreCalculator is a line-for-line port (ScoreParityTest replays this function against it). */
function computeRiskScoreLocal(){
  if(!dataset) return null;
  let sum=0, weight=0;''')

# ---------------------------------------------------------------- tiles / cards / section counts carry provenance badges
rep('<div class="kpi-label">${esc(def.label)}</div>', '<div class="kpi-label">${esc(def.label)}${integrityBadge(\'macro.\'+def.id)}</div>')
rep('<span class="bias-asset">${esc(def.label)}</span>', '<span class="bias-asset">${esc(def.label)}${integrityBadge(\'bias.\'+def.id)}</span>')
rep("$('#eventsCount').textContent = list.length ? list.length + ' items' : '';", "$('#eventsCount').innerHTML = list.length ? list.length + ' items' + sectionNote('events') : '';")
rep("$('#narrativesCount').textContent = list.length ? list.length + ' active' : '';", "$('#narrativesCount').innerHTML = list.length ? list.length + ' active' + sectionNote('narratives') : '';")
rep("$('#watchlistCount').textContent = full.length ? full.length + ' items' : '';", "$('#watchlistCount').innerHTML = full.length ? full.length + ' items' + sectionNote('watchlist') : '';")
rep("$('#summaryCount').textContent = bullets.length ? bullets.length + ' points' : '';", "$('#summaryCount').innerHTML = bullets.length ? bullets.length + ' points' + sectionNote('summary') : '';")
rep('<span class="pos-label">${esc(p.label)}</span>', '<span class="pos-label">${esc(p.label)}${integrityBadge(\'pos.\'+p.id)}</span>')

# ---------------------------------------------------------------- status pills
rep("  pills.push(isParsing ? `<span class=\"pill pill--parsing\">Parsing…</span>`", r'''  if(dataset && !isDemo){
    if(validatorMode === 'java' && integrity){
      const sm = integrity.summary || {}, nf = (lastFlags||[]).length, warnFlags = (lastFlags||[]).filter(f=>f.severity==='warn').length;
      const failed = (sm.EXTRACTION_FAILED||0) + (sm.REJECTED_INVALID||0);
      const bad = (sm.STALE||0) + failed + warnFlags > 0;
      const txt = `Integrity: ${sm.UPDATED||0} new \u00B7 ${sm.CARRIED_FORWARD||0} carried` + ((sm.STALE||0) ? ` \u00B7 ${sm.STALE} stale` : '') + (failed ? ` \u00B7 ${failed} failed` : '') + (nf ? ` \u00B7 ${nf} flag${nf===1?'':'s'}` : '');
      pills.push(`<span class="pill${bad?' pill--warn':''}" data-detail-type="integrity" data-detail-id="all" title="Click for provenance, validation notes and consistency flags">${txt}</span>`);
    } else if(validatorMode === 'browser-fallback'){
      pills.push(`<span class="pill pill--warn" data-detail-type="integrity" data-detail-id="all" title="Java validation service was unavailable for the last update">Validator: Browser Fallback</span>`);
    }
  }
  pills.push(isParsing ? `<span class="pill pill--parsing">Parsing…</span>`''')

# ---------------------------------------------------------------- detail modal
rep("detailSourceBlock(v.src) + detailExplainBlock('macro');", "detailSourceBlock(v.src) + integrityBlock('macro.'+id) + detailExplainBlock('macro');")
rep("+ detailSourceBlock(v.src) + detailExplainBlock('bias');", "+ detailSourceBlock(v.src) + integrityBlock('bias.'+id) + detailExplainBlock('bias');")
rep("+ detailSourceBlock(p.src) + detailExplainBlock('pos');", "+ detailSourceBlock(p.src) + integrityBlock('pos.'+id) + detailExplainBlock('pos');")
rep("+ detailSourceBlock(e.src) + detailExplainBlock('event');", "+ detailSourceBlock(e.src) + integrityBlock('events') + detailExplainBlock('event');")
rep("detailSourceBlock(n.src) + detailExplainBlock('narrative');", "detailSourceBlock(n.src) + integrityBlock('narratives') + detailExplainBlock('narrative');")
rep("detailSourceBlock(w.src) + detailExplainBlock('watchlist');", "detailSourceBlock(w.src) + integrityBlock('watchlist') + detailExplainBlock('watchlist');")
rep("+ detailSourceBlock(t.src) + detailExplainBlock('today');", "+ detailSourceBlock(t.src) + integrityBlock('today') + detailExplainBlock('today');")
rep("detailRow('Bullet', b?esc(b):'—') + detailExplainBlock('summary');", "detailRow('Bullet', b?esc(b):'—') + integrityBlock('summary') + detailExplainBlock('summary');")
rep("+ rows + detailExplainBlock('risk-gauge');", "+ rows + scoreDisclosureBlock() + (isDemo ? '' : (lastFlags||[]).filter(f=>f.scope==='score'||f.scope==='pos'||f.scope==='bias').map(flagHtml).join('')) + detailExplainBlock('risk-gauge');")
rep("  } else { return; }\n  $('#detailEyebrow')",
    "  } else if(type==='integrity'){\n    eyebrow='Data Integrity'; title='Provenance, validation & consistency';\n    body = integritySummaryBlock();\n  } else { return; }\n  $('#detailEyebrow')")
rep("    : 'Not found in the pasted text \\u2014 this value is either still showing an earlier paste\\u2019s data, or hasn\\u2019t been mentioned yet.';",
    "    : (integrity ? 'No source excerpt was recorded for this value \\u2014 see Data Integrity below for where the value itself came from.'\n       : 'Not found in the pasted text \\u2014 this value is either still showing an earlier paste\\u2019s data, or hasn\\u2019t been mentioned yet.');")

# ---------------------------------------------------------------- persistence carries provenance
rep("{ ds, demo, savedAt: Date.now() }", "{ ds, demo, savedAt: Date.now(), integrity: demo ? null : integrity }")

# ---------------------------------------------------------------- orchestration: validate/reconcile/score in Java, legacy path as fallback
rep("    const fresh = normalizeDataset(rawExtracted);\n    const { data, foundCount } = mergeWithPrevious(fresh, dataset);\n    if(foundCount === 0){",
    "    const outcome = await reconcileUpdate(rawExtracted, rawText, usedFallback, opts);\n    const { data, foundCount } = outcome;\n    if(foundCount === 0){")
rep("      dataset = data;\n      isDemo = false;\n      lastUpdatedAt = new Date();",
    "      dataset = data;\n      applyBackendResult(outcome);\n      isDemo = false;\n      lastUpdatedAt = new Date();")
rep("""      if(usedFallback){
        showError(`Groq summarization was unavailable, so this update used the local backup parser instead (figures may be less nuanced than usual). Reason: ${groqFailureReason}`, 'warn');
      } else if(lastPartialWarning){
        showError(lastPartialWarning, 'warn');
      }""", """      let banner = usedFallback
        ? `Groq summarization was unavailable, so this update used the local backup parser instead (figures may be less nuanced than usual). Reason: ${groqFailureReason}`
        : (lastPartialWarning || null);
      if(outcome.notice) banner = banner ? banner + ' | ' + outcome.notice : outcome.notice;
      if(banner) showError(banner, 'warn');""")

rep("async function handleNewIntelligence(rawText, opts){", r'''/* Sends the raw extraction to the Java backend (validate -> reconcile with what's loaded -> score -> consistency checks).
   If the backend is unreachable or answers with something unusable, falls back to the original in-browser
   normalizeDataset + mergeWithPrevious so the terminal keeps working; the result is then labelled as unverified. */
async function reconcileUpdate(rawExtracted, rawText, usedFallback, opts){
  const useStatus = !usedFallback && lastExtractionStatus;
  const payload = {
    runId: newRunId(),
    fresh: rawExtracted,
    extraction: {
      engine: usedFallback ? 'local' : 'groq',
      sections: useStatus ? { macro:lastExtractionStatus.macro, events:lastExtractionStatus.events } : { macro:'ok', events:'ok' },
      errors: useStatus ? lastExtractionStatus.errors : {}
    },
    previous: dataset,
    previousIntegrity: integrity,
    previousIsDemo: isDemo,
    previousSavedAt: lastUpdatedAt ? lastUpdatedAt.toISOString() : null,
    sourceText: rawText,
    sourceLabel: opts.sourceLabel || '',
    sourceAsOf: opts.sourceAsOf || null
  };
  try{
    const resp = await backendPost('/api/process', payload, 20000);
    if(!isValidProcessResponse(resp)) throw new Error('Backend returned an unexpected response shape.');
    const parts = [];
    const sm = resp.integrity.summary || {};
    if(resp.appliedCount === 0 && resp.foundCount > 0) parts.push('Signals were found but none were applied (the source is older than data already loaded, or every value failed validation).');
    else if(sm.REJECTED_INVALID > 0) parts.push(`${sm.REJECTED_INVALID} field${sm.REJECTED_INVALID===1?'':'s'} failed validation and kept earlier values`);
    const warnFlags = (resp.flags || []).filter(f=>f.severity==='warn').length;
    if(warnFlags) parts.push(`${warnFlags} consistency flag${warnFlags===1?'':'s'} to review (see the Integrity pill)`);
    return { data: resp.dataset, foundCount: resp.foundCount, backend: resp, notice: parts.length ? parts.join('; ') : null };
  }catch(err){
    console.warn('Java backend unavailable; using the built-in browser validation and merge', err);
    const fresh = normalizeDataset(rawExtracted);
    const merged = mergeWithPrevious(fresh, dataset);
    return { data: merged.data, foundCount: merged.foundCount, backend: null,
      notice: 'The Java validation service was unavailable, so this update used the built-in browser validation and merge (no provenance tracking or consistency checks).' };
  }
}
function applyBackendResult(o){
  datasetVersion++;
  if(o.backend){
    integrity = o.backend.integrity; lastFlags = o.backend.flags || []; lastIssues = o.backend.issues || [];
    validatorMode = 'java';
    serverScore = { ref: dataset, value: o.backend.score.value, detail: o.backend.score };
  }else{
    integrity = null; lastFlags = []; lastIssues = []; validatorMode = 'browser-fallback'; serverScore = null;
  }
}
/* Re-ages provenance (time passes; persisted data predates this session). Never changes values. The result is
   discarded if the dataset was replaced or an update is running while the request was in flight. */
async function reevaluateLoaded(savedAtMs){
  if(!dataset || isDemo || isParsing) return;
  const ver = datasetVersion, ref = dataset;
  try{
    const resp = await backendPost('/api/reevaluate', { dataset, integrity, savedAt: new Date(savedAtMs).toISOString(), isDemo:false }, 8000);
    if(!resp || !resp.integrity || !resp.score || ver !== datasetVersion || ref !== dataset || isParsing) return;
    integrity = resp.integrity; lastFlags = resp.flags || []; validatorMode = 'java';
    serverScore = { ref: dataset, value: resp.score.value, detail: resp.score };
    renderAll();
  }catch(e){ console.warn('provenance re-evaluation skipped', e); }
}

async function handleNewIntelligence(rawText, opts){''')

# ---------------------------------------------------------------- sample dataset resets backend-derived state
rep("dataset = SAMPLE_DATASET; isDemo = true;",
    "dataset = SAMPLE_DATASET; isDemo = true; integrity = null; lastFlags = []; lastIssues = []; validatorMode = null; serverScore = null; datasetVersion++;")

# ---------------------------------------------------------------- init: health check, persisted provenance, re-evaluation
rep("      dataset = persisted.ds;\n      isDemo = !!persisted.demo;\n      lastUpdatedAt = persisted.savedAt ? new Date(persisted.savedAt) : new Date();\n      renderAll();\n    }\n  }catch(e){ console.warn('no persisted dataset', e); }\n}",
    r'''      dataset = persisted.ds;
      isDemo = !!persisted.demo;
      integrity = isDemo ? null : (persisted.integrity || null);
      datasetVersion++;
      lastUpdatedAt = persisted.savedAt ? new Date(persisted.savedAt) : new Date();
      renderAll();
    }
  }catch(e){ console.warn('no persisted dataset', e); }
  // backend health (non-blocking for the UI): tells the assistant/banners whether AI + news are available
  try{
    const h = await backendFetch('/api/health', { method:'GET' }, 4000);
    backendHealth = h.ok ? Object.assign({ reachable:true }, await h.json()) : { reachable:false };
  }catch(e){ backendHealth = { reachable:false }; }
  if(backendHealth.reachable === false){
    showError('The Java backend isn\u2019t reachable \u2014 AI extraction, live news and the assistant are unavailable until it is running. Pasting text still works using the local parser.', 'warn');
  }else if(backendHealth.groqConfigured === false){
    showError('The Java backend has no GROQ_API_KEY configured \u2014 updates will use the local parser, and the assistant is unavailable.', 'warn');
  }
  if(backendHealth.reachable && dataset && !isDemo && !isParsing) reevaluateLoaded(lastUpdatedAt ? lastUpdatedAt.getTime() : Date.now());
  setInterval(()=>{ if(integrity && !isParsing && !isDemo) reevaluateLoaded(lastUpdatedAt ? lastUpdatedAt.getTime() : Date.now()); }, 5 * 60 * 1000);
}''')

out = s.replace('\n', '\r\n') if crlf else s
open(dst, 'w', encoding='utf-8', newline='').write(out)
print('patched OK; crlf preserved =', crlf, '; size', len(raw), '->', len(out))
