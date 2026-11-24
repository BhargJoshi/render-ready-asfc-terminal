# Backend API (v1)

All bodies are JSON. Errors: `{"error":{"code":"…","message":"…"}}` (Groq relay errors use `{"error":{"message","type","code"}}`, news relay errors use newsdata's `{"status":"error","results":{"message","code"}}`, so the existing browser error handling keeps working). POSTs need `Content-Type: application/json`. Requests with a disallowed `Origin`/`Host` get 403.

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | `{ok, apiVersion, groqConfigured, newsConfigured, staleAfterMinutes}` — never returns secrets |
| `POST /api/process` | validate → reconcile → score → consistency |
| `POST /api/reevaluate` | re-age provenance of a loaded dataset; values never change |
| `POST /api/score` | legacy score formula on a raw `bias` object (for parity checks) |
| `POST /api/groq/chat` | relay to Groq chat completions (key added server-side) |
| `GET /api/news/search?q=` | relay to newsdata.io (key/category/language/size added server-side) |
| `GET /` | static front end |

## POST /api/process
Request
```json
{ "runId": "uuid", "fresh": { "…raw extraction as Groq/local parser produced it…" },
  "extraction": { "engine": "groq|local", "sections": { "macro": "ok|failed|not_run", "events": "ok|failed|not_run" },
                  "errors": { "macro": "msg", "events": "msg" } },
  "previous": { "…dataset or null…" }, "previousIntegrity": { "…or null…" }, "previousIsDemo": false,
  "previousSavedAt": "ISO-8601|null", "sourceText": "text analysed (for traceability checks)", "sourceAsOf": "ISO-8601|null" }
```
`sections.macro` covers meta/macro/bias/matrix/pos; `sections.events` covers events/narratives/watchlist/today/summary.
`sourceAsOf` (newest article time) lets the server refuse to overwrite newer data with older news.

Response 200
```json
{ "apiVersion": 1, "runId": "…", "processedAt": "ISO",
  "dataset": { "meta","macro","bias","matrix","pos","events","narratives","watchlist","today","summary" },   // identical schema to the browser's
  "integrity": { "fields": { "macro.fed": {"status","reason","hasValue","updatedAt","asOf","engine","ageMinutes","stale","issues"} … },
                 "sections": {…}, "summary": {"UPDATED":n,…}, "staleAfterMinutes": {…} },
  "score": { "value": 100|null, "inputs": […], "defaultedConfidence": ["gold"], "formula": "…" },
  "flags": [ {"id","severity":"info|warn","scope","refs":[…],"message","assumption","heuristic"} ],
  "issues": [ {"code","severity":"info|warn|error","path","message"} ], "issueCounts": {…},
  "foundCount": 14, "appliedCount": 14 }
```
Field keys: `macro.<id>`, `bias.<id>` (bias + driver matrix move together), `pos.<id>`, `events`, `narratives`, `watchlist`, `today`, `summary`, `meta.source|session|headline`.

### Field status
| Status | Meaning |
|---|---|
| `UPDATED` | valid value from this update |
| `CARRIED_FORWARD` | not mentioned this time (or newer data already loaded / update too thin); earlier value kept, **not re-verified** |
| `STALE` | a value older than the threshold; `reason` keeps the underlying cause (`not_mentioned`, `aged`, …) |
| `EXTRACTION_FAILED` | the extraction for that section failed; earlier value (if any) kept |
| `REJECTED_INVALID` | the new value failed validation; earlier value (if any) kept |
| `MISSING` | never populated |

### Validation rules (summary)
Bias label ∈ Bullish/Bearish/Neutral (case-insensitive, else entry rejected) · confidence/strength numeric in 0–100 (else *unknown*, never clamped or rescaled) · macro `cur` must contain a number (else rejected), `prev`/`cons` without a number dropped · placeholders (`N/A`, `none`, `unknown`, …) are *no value* and can never overwrite real data · trend ∈ up/down/flat/na · tone enums · list items need their headline field, duplicates dropped, caps as in the browser · warnings (value kept): implausible range, number not found in the source text, excerpt not traceable to the source text, missing source reference, unknown asset token.

### Consistency rules (ids)
Structural: `BIAS_MATRIX_LABEL_MISMATCH`, `BIAS_DRIVER_DIRECTION_GAP`, `POS_VS_ASSET_BIAS`, `MACRO_TREND_VS_VALUES`.
Conventional-relationship (info, with explicit `assumption`, `heuristic:true`): `BONDS_VS_YIELDS`, `GOLD_VS_REAL_RATES_DOLLAR`, `EQUITY_BULLISH_IN_HIGH_VOL`, `POS_RISK_VS_SCORE`, `POS_DOLLAR_VS_DXY`, `HIGH_CONFIDENCE_NEUTRAL`, `MACRO_NOTE_VS_TREND`, `BIAS_REVERSAL_VS_PREVIOUS`, `LARGE_CHANGE_VS_PREVIOUS`.
Disclosures: `SCORE_USES_NON_FRESH_INPUTS`, `SCORE_CONFIDENCE_DEFAULTED`.

## Coupling to keep in sync
`schema/Schema.java` mirrors the browser's `MACRO_DEFS`, `ASSET_DEFS`, `POS_DEFS`, the enum values in the Groq response schema, and the list caps in `normalizeDataset`. Change them together.
