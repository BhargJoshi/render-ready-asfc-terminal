# Change summary

## Files
**Added** — `backend/` (Java 17+ service: `schema`, `validation`, `reconcile`, `scoring`, `consistency`, `service`, `upstream`, `http`, `config`; 61 tests), `backend/build.sh`, `backend/run.sh`, `backend/pom.xml`, `test-e2e/` (browser-level test), `scripts/patch_frontend.py`, `scripts/gen-score-fixtures.js`, `docs/`, `README.md`, `.env.example`, `.gitignore`.
**Modified** — `frontend/index.html` (≈ +20 KB; only the sites below). **Kept for rollback** — `frontend/ashford-market-terminal.original.html` (keys redacted).

### Edits inside the page
1. `CONFIG`: Groq/newsdata keys and provider URLs removed; `BACKEND_URL` added (same origin by default).
2. `callGroq` / `fetchLiveNews`: same logic, but call `/api/groq/chat` and `/api/news/search`; no `Authorization` header; extra handling for backend-originated errors (503 not configured, 502/504 upstream, 403 origin).
3. `summarizeWithGroq`: records which half succeeded/failed (`lastExtractionStatus`).
4. `handleNewIntelligence`: `normalizeDataset + mergeWithPrevious` replaced by `reconcileUpdate()` which calls `/api/process` and **falls back to the original two functions** if the backend is unreachable or returns an unusable shape.
5. `computeRiskScore`: returns the Java score when it belongs to the current dataset object, otherwise the original formula (kept verbatim as `computeRiskScoreLocal`).
6. UI additions: Integrity pill, provenance badges, "Data Integrity" detail sections, assistant digest notes about non-fresh values/flags.
7. Persistence stores provenance; load re-ages it via `/api/reevaluate` (discarded if the dataset changed meanwhile).
8. Unknown narrative strength renders as 0-width/"—" instead of a fabricated 50%; article add passes the newest `publishedAt`; assistant heads-up now reflects backend health instead of key format.

## Deliberate behaviour differences (all only on the Java path)
| Before (browser) | Now (Java) | Why |
|---|---|---|
| Invalid bias label kept | Entry rejected, earlier value kept, reported | Unknown labels broke colouring and scoring |
| Confidence outside 0–100 kept and fed to the score | Treated as unknown (not clamped/rescaled), label kept | 0.85 could mean 0.85% or 85% |
| `"N/A"`/`"none"` in a reading counted as data and overwrote good values | Treated as no value | Silent overwrite of valid data |
| Reading with no digits accepted | Rejected | Not a reading |
| Missing narrative strength → 50, missing importance → "Low", missing tone → "neutral" | `null` / empty / `none` | "Unknown must not become neutral" |
| Demo sample values filled gaps in a "Live" dataset | Demo is never used as a fallback | Old data presented as live |
| Carried-over values indistinguishable from new | Per-field status, age, reason | Core requirement |
| Duplicate list items within one extraction kept | Dropped | Duplicate processing |
| News articles older than loaded data could overwrite it | Kept earlier value, flagged | Accidental overwrite |
| `foundCount` counted rejected values | Counts valid values only | Consistency |
**Score:** formula and output are identical (4,000-case parity). One tension is *not* changed: when an asset has a bias but no confidence, the original formula weights it at 50%. The task required an unchanged formula, so this stays — but it is now disclosed in the gauge detail and as flag `SCORE_CONFIDENCE_DEFAULTED`.

## Verification results
| Check | Result |
|---|---|
| Backend compiles (`javac --release 17 -Xlint:all`) | clean |
| Unit/integration tests (`backend/build.sh test`) | **61/61**, stable over repeated runs |
| Score parity vs original JavaScript | **4,000/4,000** (incl. no-score cases) |
| Mutation sanity check (3 deliberate bugs) | caught (3, 10 and 2 failing tests respectively) |
| `run.sh` starts, serves page/API, rejects foreign origin | verified (200 / 200 / 403) |
| Browser-level e2e (real page JS in jsdom ↔ real Java ↔ mock Groq/news) | **55/55** over two runs |
| e2e scenarios | success path, partial follow-up, half-failed extraction, Groq rejecting all calls, Groq key missing, backend killed mid-session, overlapping updates, news, assistant, upload, demo isolation, legacy persisted data, older-source protection |

## Known limitations (please read)
* **No live Groq/newsdata.io call was made** (no keys/network here). The relay is tested against mocks that mimic the OpenAI-compatible format and rate-limit headers; first real use is the remaining check.
* **jsdom, not a real browser:** DOM content, state and flows are verified; visual layout/CSS of the new pills and badges is **not**. Please eyeball them once.
* **Maven descriptor unverified** (Maven Central unreachable in the sandbox); `build.sh` is the verified path.
* **With Java down, AI extraction and live news are unavailable** — a direct consequence of keeping keys server-side. The local parser and the original browser validation/merge/score still work, and the result is labelled "Browser Fallback" with provenance cleared (never faked). On that path the original defaults (e.g. strength 50) still apply.
* **Persistence** still uses `window.storage` exactly as before, which only exists in some hosts (e.g. Claude artifacts). In a plain browser nothing persists across reloads, so cross-session provenance needs that host (or a localStorage shim I deliberately did not add, to avoid changing behaviour).
* Staleness thresholds (12 h / 7 d) are defaults I chose; consistency rules are heuristics that can produce false positives (each can be disabled; relationship rules are `info` with a stated assumption). The note-wording rule is a simple word check.
* Traceability checks only warn: a model may legitimately reformat a number or lightly rephrase an excerpt.
* The local fallback parser's own behaviour (e.g. Neutral/50 when an asset is mentioned without signals) is unchanged.
* No authentication/rate limiting on the backend (local single-user design). `.docx`/`.pdf` upload paths use CDN libraries and were not exercised (`.txt` was).
