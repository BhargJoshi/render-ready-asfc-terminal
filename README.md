# Ashford Market Intelligence Terminal — Java backend integration

The terminal's front end is unchanged in look and workflow. A small Java service now sits behind it and owns:

| Concern | Where it lives now |
|---|---|
| Groq + newsdata.io credentials | Java backend only (environment variables). **No key is in the page any more.** |
| Validation / normalisation of extracted data | Java (`DatasetValidator`) |
| Merging new data with what is already loaded, with per-field provenance | Java (`Reconciler`) |
| Composite risk/positioning score | Java (`RiskScoreCalculator`, a line-for-line port of the original formula) |
| Contradiction flags | Java (`ConsistencyChecker`) — annotate only, never edit AI content |
| AI interpretation (prompts, schemas, retry cascade), local fallback parser, rendering, modals, search, upload, assistant | **Still the existing JavaScript** |

```
 browser (existing HTML/JS) ──same origin──► Java backend ──► Groq / newsdata.io   (keys injected here)
        │  POST /api/process   validate → reconcile → score → consistency flags
        │  POST /api/groq/chat, GET /api/news/search   (relays; status codes & rate-limit headers preserved)
        └─ if Java is unreachable: original in-browser normalize + merge + score run unchanged (labelled "Browser Fallback")
```

## Quick start

**Prerequisites:** JDK 17+ (built and tested on 21). Node is only needed for the optional browser-level test.

```bash
cp .env.example .env            # then put your keys in .env (never commit it)
#   GROQ_API_KEY=...            #   NEWSDATA_API_KEY=...
backend/build.sh                # compile (javac + Jackson jars, see "Building" below)
backend/run.sh                  # starts the backend AND serves the front end
```
Open **http://localhost:8080/** (the backend serves `frontend/index.html`, so the page and API are same-origin; no CORS needed).

> **Rotate your keys.** The file you gave me contained live Groq and newsdata.io keys in plain text. The copy kept for rollback has them replaced by placeholders; the old keys should be treated as exposed and replaced.

### Building
* `backend/build.sh` — needs `jackson-core/databind/annotations` jars (looked up in `$LIB_DIR`, `backend/lib`, `/usr/share/java`; on Debian/Ubuntu `apt install libjackson2-databind-java`, otherwise download them from Maven Central into `backend/lib`). `./build.sh test` also needs the JUnit 5 console launcher jar.
* `backend/pom.xml` — Maven build (`mvn -B verify -f backend/pom.xml`); the GitHub Actions workflow in `.github/workflows/maven.yml` runs Maven verification and builds the Docker image on pushes and pull requests to `main`.

### Configuration (environment variables)
| Variable | Default | Purpose |
|---|---|---|
| `GROQ_API_KEY` | – | Required for AI extraction and the assistant. Without it the terminal falls back to its local parser. |
| `NEWSDATA_API_KEY` | – | Required for live news search. |
| `PORT` / `BIND_HOST` | `8080` / `127.0.0.1` | Keep loopback unless you put authentication/TLS in front (see Security). |
| `GROQ_MODEL` | page's `CONFIG.GROQ_MODEL` | Pins the model server-side. |
| `GROQ_TIMEOUT_SECONDS`, `GROQ_MAX_COMPLETION_TOKENS` | `40`, `8192` | Relay timeout and token cap. |
| `NEWS_CATEGORY` | `business,politics` | Same value the page used before. |
| `STALE_AFTER_MINUTES`, `STALE_AFTER_MINUTES_SLOW` | `720`, `10080` | Age at which values are labelled STALE (slow = Fed/CPI/PPI). Judgement calls — tune them. |
| `CONSISTENCY_DISABLED_RULES` | – | Comma-separated rule ids to switch off. |
| `ALLOWED_ORIGINS`, `ALLOWED_HOSTS` | loopback on `PORT` | Extra browser origins / Host headers accepted. |
| `STATIC_DIR` | `../frontend` | Folder served at `/`. |

**Opening the HTML from `file://` instead:** the page defaults to `http://localhost:8080` in that case, but browsers send `Origin: null`, which the backend rejects by default. Add `ALLOWED_ORIGINS=null` only if you accept that any local file/sandboxed page could then call the API. Serving through the backend is the recommended mode.

## What you will see in the UI
* **Integrity pill** in the header (`Integrity: 14 new · 6 carried · 2 stale · 1 flag`) → click for provenance, validation notes and consistency flags. `Validator: Browser Fallback` appears instead when Java was unavailable for the last update.
* Small **Carried / Stale / Not refreshed / Rejected update** badges on macro tiles, bias cards, positioning rows and list headers whenever a value is *not* from the latest update. Fresh values show nothing, so a normal first paste looks exactly as before.
* Every detail modal gets a **Data Integrity** section (status, age, who extracted it, validation notes, relevant flags). The gauge modal discloses assumed confidences and non-refreshed inputs.

## Tests
```bash
backend/build.sh test        # 61 JUnit tests (validation, reconciliation, score parity, consistency, partial failures, HTTP layer)
cd test-e2e && npm install && cd .. && test-e2e/run-e2e.sh   # 55 browser-level checks (see below)
```
* `ScoreParityTest` replays **4,000 randomised cases generated by running the original JavaScript `computeRiskScore()`** against the Java calculator (regenerate with `scripts/gen-score-fixtures.js`).
* The e2e test loads the real patched page over HTTP from the real Java server in jsdom and drives paste, partial updates, half-failed extraction, Groq down, Groq not configured, backend killed mid-session, news search, assistant chat, upload, demo data, legacy persisted data and overlapping updates. Groq and newsdata.io are **mocked** (no live keys/network were available).

## Rollback
* `frontend/ashford-market-terminal.original.html` — the original page (keys redacted; paste yours back in to use it standalone).
* The patched page is generated by `scripts/patch_frontend.py original.html patched.html`; every edit asserts its anchor matches exactly once.
* The original browser logic (`normalizeDataset`, `mergeWithPrevious`, `computeRiskScoreLocal`) is still in the patched page and is the automatic fallback.

## Security notes
Single-user, local tool: no authentication or rate limiting. It binds to loopback, rejects foreign `Origin`/`Host` headers (so other websites cannot spend your keys or rebind DNS to it), requires `application/json` on POSTs, caps body size, forwards only an allow-list of Groq request fields, and never logs bodies or keys. If you expose it beyond localhost, add authentication and TLS first.
