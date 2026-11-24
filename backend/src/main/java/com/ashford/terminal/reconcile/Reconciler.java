package com.ashford.terminal.reconcile;

import com.ashford.terminal.schema.Schema;
import com.ashford.terminal.util.Json;
import com.ashford.terminal.validation.DatasetShape;
import com.ashford.terminal.validation.Issue;
import com.ashford.terminal.validation.Validated;
import com.ashford.terminal.validation.Validated.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Combines a validated fresh extraction with previously loaded data and records, for every dashboard field,
 * WHY it holds the value it holds.
 *
 * Rules (per field):
 *  1. Section extraction failed            -> keep previous value (if any), status EXTRACTION_FAILED
 *  2. Fresh value valid
 *       2a. source older than the stored value -> keep previous, CARRIED_FORWARD (reason older_source)
 *       2b. summary update too thin             -> keep previous, CARRIED_FORWARD (reason thin_update) [legacy rule kept]
 *       2c. otherwise                           -> take fresh, UPDATED
 *  3. Fresh value present but invalid      -> keep previous (if any), REJECTED_INVALID
 *  4. Fresh value absent (not mentioned)   -> keep previous, CARRIED_FORWARD; none -> MISSING
 * Any retained or updated value older than the staleness threshold is reported as STALE (with the underlying reason).
 * A previous dataset that was the demo sample is ignored entirely: demo numbers must never appear inside a live dataset.
 */
public final class Reconciler {

    public enum SectionState { OK, FAILED, NOT_RUN;
        public static SectionState parse(String s) {
            if ("failed".equalsIgnoreCase(s)) return FAILED;
            if ("not_run".equalsIgnoreCase(s)) return NOT_RUN;
            return OK;
        }
        String json() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Request(Validated validated, ObjectNode previous, JsonNode previousIntegrity, boolean previousIsDemo,
                          Instant previousSavedAt, Instant now, Instant sourceAsOf, String engine,
                          SectionState macroHalf, SectionState eventsHalf, String macroError, String eventsError,
                          String runId, Staleness staleness) {}

    public record Result(ObjectNode dataset, ObjectNode integrity, int foundCount, int appliedCount, List<Issue> issues) {}

    private enum Cause {
        NEW("new_value"), NOT_MENTIONED("not_mentioned"), EXTRACTION_FAILED("extraction_failed"), SECTION_NOT_RUN("section_not_run"),
        REJECTED_INVALID("rejected_invalid"), OLDER_SOURCE("older_source"), THIN_UPDATE("thin_update");
        final String reason;
        Cause(String r) { reason = r; }
    }

    // ------------------------------------------------------------------ reconcile

    public Result reconcile(Request q) {
        Validated v = q.validated();
        boolean prevUsable = q.previous() != null && !q.previousIsDemo();
        ObjectNode prev = prevUsable ? q.previous() : DatasetShape.empty();
        JsonNode prevFields = prevUsable && q.previousIntegrity() != null ? q.previousIntegrity().get("fields") : null;

        List<Issue> issues = new ArrayList<>();
        if (q.previous() != null && q.previousIsDemo())
            issues.add(Issue.info("PREVIOUS_DEMO_IGNORED", "$", "The sample/demo dataset was not used as a fallback for fields this update did not find."));
        if (q.macroHalf() == SectionState.FAILED)
            issues.add(Issue.warn("SECTION_FAILED", "macro", "Macro / bias / positioning extraction failed" + suffix(q.macroError()) + "; those fields were not refreshed."));
        if (q.eventsHalf() == SectionState.FAILED)
            issues.add(Issue.warn("SECTION_FAILED", "events", "Events / narratives / summary extraction failed" + suffix(q.eventsError()) + "; those fields were not refreshed."));

        Ctx c = new Ctx(q, prevFields, issues);
        ObjectNode ds = Json.obj();
        ObjectNode fields = Json.obj();
        int[] applied = {0};

        // meta
        ObjectNode meta = ds.putObject("meta");
        for (String k : List.of("source", "session", "headline")) {
            Validated.Entry<String> fe = v.meta.getOrDefault(k, Validated.Entry.absent(List.of()));
            String pv = Json.text(prev.get("meta"), k);
            Decision d = c.decide("meta." + k, Half.MACRO, fe.outcome(), !pv.isEmpty(), false);
            meta.put(k, d.useFresh ? fe.value() : pv);
            if (d.useFresh) applied[0]++;
            fields.set("meta." + k, c.entry("meta." + k, d, !pv.isEmpty(), fe.issues()));
        }
        // macro
        ObjectNode macro = ds.putObject("macro");
        for (Schema.Def def : Schema.MACRO) {
            String key = "macro." + def.id();
            Validated.Entry<ObjectNode> fe = v.macro.getOrDefault(def.id(), Validated.Entry.absent(List.of()));
            JsonNode pe = prev.get("macro").get(def.id());
            boolean prevHas = !Json.text(pe, "cur").isEmpty();
            Decision d = c.decide(key, Half.MACRO, fe.outcome(), prevHas, false);
            macro.set(def.id(), d.useFresh ? fe.value() : pe.deepCopy());
            if (d.useFresh) applied[0]++;
            fields.set(key, c.entry(key, d, prevHas, fe.issues()));
        }
        // bias + matrix (always replaced together, as the browser merge did)
        ObjectNode bias = ds.putObject("bias"), matrix = ds.putObject("matrix");
        for (Schema.Def def : Schema.ASSETS) {
            String key = "bias." + def.id();
            Validated.Entry<Validated.BiasPair> fe = v.bias.getOrDefault(def.id(), Validated.Entry.absent(List.of()));
            JsonNode pb = prev.get("bias").get(def.id()), pm = prev.get("matrix").get(def.id());
            boolean prevHas = !Json.text(pb, "b").isEmpty();
            Decision d = c.decide(key, Half.MACRO, fe.outcome(), prevHas, false);
            bias.set(def.id(), d.useFresh ? fe.value().bias() : pb.deepCopy());
            matrix.set(def.id(), d.useFresh ? fe.value().matrix() : pm.deepCopy());
            if (d.useFresh) applied[0]++;
            fields.set(key, c.entry(key, d, prevHas, fe.issues()));
        }
        // positioning
        ArrayNode pos = ds.putArray("pos");
        for (int i = 0; i < Schema.POS.size(); i++) {
            Schema.Def def = Schema.POS.get(i);
            String key = "pos." + def.id();
            Validated.Entry<ObjectNode> fe = v.pos.getOrDefault(def.id(), Validated.Entry.absent(List.of()));
            JsonNode pe = prev.get("pos").get(i);
            boolean prevHas = !Json.text(pe, "v").isEmpty();
            Decision d = c.decide(key, Half.MACRO, fe.outcome(), prevHas, false);
            pos.add(d.useFresh ? fe.value() : pe.deepCopy());
            if (d.useFresh) applied[0]++;
            fields.set(key, c.entry(key, d, prevHas, fe.issues()));
        }
        // lists
        listField(c, ds, fields, applied, prev, "events", v.events);
        listField(c, ds, fields, applied, prev, "narratives", v.narratives);
        listField(c, ds, fields, applied, prev, "watchlist", v.watchlist);
        listField(c, ds, fields, applied, prev, "today", v.today);

        // summary: a thin follow-up (<2 bullets) must not wipe a substantial earlier recap (legacy rule, kept)
        ArrayNode prevBullets = (ArrayNode) prev.get("summary").get("bullets");
        boolean thin = v.bullets.isValid() && v.bullets.value().size() < 2 && !prevBullets.isEmpty();
        Decision sd = c.decide("summary", Half.EVENTS, v.bullets.outcome(), !prevBullets.isEmpty(), thin);
        ds.putObject("summary").set("bullets", sd.useFresh ? v.bullets.value() : prevBullets.deepCopy());
        if (sd.useFresh) applied[0]++;
        fields.set("summary", c.entry("summary", sd, !prevBullets.isEmpty(), v.bullets.issues()));

        // reorder to the browser's key order: meta, macro, bias, matrix, pos, events, narratives, watchlist, today, summary
        ObjectNode ordered = Json.obj();
        for (String k : List.of("meta", "macro", "bias", "matrix", "pos", "events", "narratives", "watchlist", "today", "summary"))
            ordered.set(k, ds.get(k));

        ObjectNode integrity = Json.obj();
        integrity.put("schemaVersion", 1);
        integrity.put("validator", "java");
        integrity.put("processedAt", q.now().toString());
        integrity.put("runId", q.runId() == null ? "" : q.runId());
        integrity.put("engine", q.engine());
        if (q.sourceAsOf() == null) integrity.putNull("sourceAsOf"); else integrity.put("sourceAsOf", q.sourceAsOf().toString());
        ObjectNode st = integrity.putObject("staleAfterMinutes");
        st.put("fast", q.staleness().fastMinutes()); st.put("slow", q.staleness().slowMinutes());
        ObjectNode sec = integrity.putObject("sections");
        section(sec, "macro", q.macroHalf(), q.macroError());
        section(sec, "events", q.eventsHalf(), q.eventsError());
        integrity.set("fields", fields);
        integrity.set("summary", tally(fields));

        return new Result(ordered, integrity, v.foundCount(), applied[0], issues);
    }

    private void listField(Ctx c, ObjectNode ds, ObjectNode fields, int[] applied, ObjectNode prev, String key, Validated.Entry<ArrayNode> fe) {
        ArrayNode pl = (ArrayNode) prev.get(key);
        Decision d = c.decide(key, Half.EVENTS, fe.outcome(), !pl.isEmpty(), false);
        ds.set(key, d.useFresh ? fe.value() : pl.deepCopy());
        if (d.useFresh) applied[0]++;
        fields.set(key, c.entry(key, d, !pl.isEmpty(), fe.issues()));
    }

    private static void section(ObjectNode sec, String name, SectionState s, String err) {
        ObjectNode o = sec.putObject(name);
        o.put("extraction", s.json());
        if (s == SectionState.FAILED && err != null && !err.isBlank()) o.put("error", err.length() > 400 ? err.substring(0, 400) : err);
    }

    private static String suffix(String err) {
        return err == null || err.isBlank() ? "" : " (" + (err.length() > 160 ? err.substring(0, 160) + "\u2026" : err) + ")";
    }

    // ------------------------------------------------------------------ re-evaluation (time passing / legacy data)

    /**
     * Re-ages an existing dataset's provenance without changing any value. Used when the browser reloads a
     * persisted dataset, and periodically. Datasets saved before provenance tracking get "legacy" entries:
     * their values are reported as carried forward from the save time - never as freshly verified.
     */
    public ObjectNode reevaluate(ObjectNode dataset, JsonNode integrity, Instant savedAt, Instant now, Staleness staleness) {
        JsonNode oldFields = integrity == null ? null : integrity.get("fields");
        ObjectNode fields = Json.obj();
        for (String key : allKeys()) {
            boolean has = hasValue(dataset, key);
            JsonNode old = oldFields == null ? null : oldFields.get(key);
            ObjectNode e = Json.obj();
            Instant updated, asOf; String engine, reason; Status status; boolean legacy = false;
            if (old == null) {
                legacy = true; updated = savedAt; asOf = null; engine = "";
                status = has ? Status.CARRIED_FORWARD : Status.MISSING;
                reason = has ? "legacy_no_provenance" : "not_mentioned";
            } else {
                updated = Json.instant(old.get("updatedAt")); asOf = Json.instant(old.get("asOf"));
                engine = Json.text(old, "engine"); reason = Json.text(old, "reason");
                try { status = Status.valueOf(Json.text(old, "status")); } catch (IllegalArgumentException ex) { status = has ? Status.CARRIED_FORWARD : Status.MISSING; }
                legacy = old.path("legacy").asBoolean(false);
                if (!has) {
                    // no value on the dashboard: only the "why there is none" statuses are meaningful
                    if (status != Status.EXTRACTION_FAILED && status != Status.REJECTED_INVALID) status = Status.MISSING;
                } else if (status == Status.MISSING) {
                    status = Status.CARRIED_FORWARD;   // a value exists that provenance does not explain: never present it as new
                    reason = "legacy_no_provenance";
                }
            }
            writeEntry(e, key, status, reason, has, updated, asOf, engine, legacy, now, staleness, null, old == null ? List.of() : codes(old.get("issues")));
            fields.set(key, e);
        }
        ObjectNode out = Json.obj();
        out.put("schemaVersion", 1); out.put("validator", "java");
        out.put("processedAt", integrity != null && integrity.has("processedAt") ? integrity.get("processedAt").asText() : now.toString());
        out.put("reevaluatedAt", now.toString());
        out.put("runId", integrity == null ? "" : Json.text(integrity, "runId"));
        out.put("engine", integrity == null ? "" : Json.text(integrity, "engine"));
        if (integrity != null && integrity.has("sections")) out.set("sections", integrity.get("sections"));
        ObjectNode stl = out.putObject("staleAfterMinutes");
        stl.put("fast", staleness.fastMinutes()); stl.put("slow", staleness.slowMinutes());
        out.set("fields", fields);
        out.set("summary", tally(fields));
        return out;
    }

    // ------------------------------------------------------------------ internals

    private enum Half { MACRO, EVENTS }
    private record Decision(boolean useFresh, Cause cause) {}

    private static List<String> allKeys() {
        List<String> k = new ArrayList<>();
        for (Schema.Def d : Schema.MACRO) k.add("macro." + d.id());
        for (Schema.Def d : Schema.ASSETS) k.add("bias." + d.id());
        for (Schema.Def d : Schema.POS) k.add("pos." + d.id());
        k.addAll(List.of("events", "narratives", "watchlist", "today", "summary", "meta.source", "meta.session", "meta.headline"));
        return k;
    }

    private static boolean hasValue(ObjectNode ds, String key) {
        int dot = key.indexOf('.');
        String head = dot < 0 ? key : key.substring(0, dot), id = dot < 0 ? "" : key.substring(dot + 1);
        switch (head) {
            case "macro": return !Json.text(ds.path("macro").get(id), "cur").isEmpty();
            case "bias": return !Json.text(ds.path("bias").get(id), "b").isEmpty();
            case "pos":
                for (JsonNode p : ds.path("pos")) if (id.equals(Json.text(p, "id"))) return !Json.text(p, "v").isEmpty();
                return false;
            case "meta": return !Json.text(ds.path("meta"), id).isEmpty();
            case "summary": return ds.path("summary").path("bullets").size() > 0;
            default: return ds.path(head).size() > 0;
        }
    }

    private static List<String> codes(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) if (n.isTextual()) out.add(n.asText());
        return out;
    }

    private static ObjectNode tally(ObjectNode fields) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Status s : Status.values()) counts.put(s.name(), 0);
        fields.fields().forEachRemaining(e -> counts.merge(Json.text(e.getValue(), "status"), 1, Integer::sum));
        ObjectNode o = Json.obj();
        counts.forEach(o::put);
        return o;
    }

    private static void writeEntry(ObjectNode e, String key, Status status, String reason, boolean hasValue, Instant updatedAt,
                                   Instant asOf, String engine, boolean legacy, Instant now, Staleness st,
                                   Instant checkedAt, List<String> issueCodes) {
        Instant base = asOf != null ? asOf : updatedAt;
        Long age = base == null ? null : Math.max(0, Duration.between(base, now).toMinutes());
        boolean stale = hasValue && age != null && age >= st.minutesFor(key);
        if (stale && status != Status.STALE) {
            // keep the underlying cause visible in "reason"
            if (status == Status.UPDATED) reason = "aged";
            status = Status.STALE;
        }
        e.put("status", status.name());
        e.put("reason", reason);
        e.put("hasValue", hasValue);
        e.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        e.put("asOf", asOf == null ? null : asOf.toString());
        e.put("engine", engine == null ? "" : engine);
        if (age == null) e.putNull("ageMinutes"); else e.put("ageMinutes", age);
        e.put("stale", stale);
        if (legacy) e.put("legacy", true);
        e.put("checkedAt", (checkedAt == null ? now : checkedAt).toString());
        ArrayNode ic = e.putArray("issues");
        for (String c : new LinkedHashSet<>(issueCodes)) { if (ic.size() < 8) ic.add(c); }
    }

    /** Per-run context: previous provenance lookup + decision + integrity entry construction. */
    private final class Ctx {
        final Request q; final JsonNode prevFields; final List<Issue> issues;
        Ctx(Request q, JsonNode prevFields, List<Issue> issues) { this.q = q; this.prevFields = prevFields; this.issues = issues; }

        Decision decide(String key, Half half, Outcome outcome, boolean prevHas, boolean thin) {
            SectionState s = half == Half.MACRO ? q.macroHalf() : q.eventsHalf();
            if (s == SectionState.FAILED) return new Decision(false, Cause.EXTRACTION_FAILED);
            if (s == SectionState.NOT_RUN) return new Decision(false, Cause.SECTION_NOT_RUN);
            if (outcome == Outcome.VALID) {
                if (prevHas && q.sourceAsOf() != null) {
                    JsonNode pf = prevFields == null ? null : prevFields.get(key);
                    Instant pBase = pf == null ? q.previousSavedAt() : firstNonNull(Json.instant(pf.get("asOf")), Json.instant(pf.get("updatedAt")));
                    if (pBase != null && q.sourceAsOf().isBefore(pBase)) {
                        issues.add(Issue.warn("OLDER_SOURCE_NOT_APPLIED", key,
                                "The new source (" + q.sourceAsOf() + ") is older than the value already loaded (" + pBase + "); the existing value was kept."));
                        return new Decision(false, Cause.OLDER_SOURCE);
                    }
                }
                if (thin) {
                    issues.add(Issue.info("THIN_UPDATE_KEPT_PREVIOUS", key, "Fewer than 2 summary bullets were extracted; the earlier, fuller recap was kept."));
                    return new Decision(false, Cause.THIN_UPDATE);
                }
                return new Decision(true, Cause.NEW);
            }
            if (outcome == Outcome.INVALID) return new Decision(false, Cause.REJECTED_INVALID);
            return new Decision(false, Cause.NOT_MENTIONED);
        }

        ObjectNode entry(String key, Decision d, boolean prevHas, List<Issue> entryIssues) {
            JsonNode pf = prevFields == null ? null : prevFields.get(key);
            Instant pUpdated = pf == null ? q.previousSavedAt() : Json.instant(pf.get("updatedAt"));
            Instant pAsOf = pf == null ? null : Json.instant(pf.get("asOf"));
            String pEngine = pf == null ? "" : Json.text(pf, "engine");
            boolean legacy = prevHas && pf == null;
            List<String> codes = new ArrayList<>();
            for (Issue i : entryIssues) if (!i.severity().equals("info") || d.cause == Cause.REJECTED_INVALID) codes.add(i.code());
            if (d.cause == Cause.OLDER_SOURCE) codes.add("OLDER_SOURCE_NOT_APPLIED");

            ObjectNode e = Json.obj();
            if (d.useFresh) {
                Instant asOf = q.sourceAsOf() != null ? q.sourceAsOf() : q.now();
                writeEntry(e, key, Status.UPDATED, Cause.NEW.reason, true, q.now(), asOf, q.engine(), false, q.now(), q.staleness(), q.now(), codes);
            } else if (prevHas) {
                Status s = d.cause == Cause.EXTRACTION_FAILED ? Status.EXTRACTION_FAILED
                        : d.cause == Cause.REJECTED_INVALID ? Status.REJECTED_INVALID : Status.CARRIED_FORWARD;
                writeEntry(e, key, s, d.cause.reason, true, pUpdated, pAsOf, pEngine, legacy, q.now(), q.staleness(), q.now(), codes);
            } else {
                Status s = d.cause == Cause.EXTRACTION_FAILED ? Status.EXTRACTION_FAILED
                        : d.cause == Cause.REJECTED_INVALID ? Status.REJECTED_INVALID : Status.MISSING;
                writeEntry(e, key, s, d.cause.reason, false, null, null, "", false, q.now(), q.staleness(), q.now(), codes);
            }
            return e;
        }
    }

    private static Instant firstNonNull(Instant a, Instant b) { return a != null ? a : b; }
}
