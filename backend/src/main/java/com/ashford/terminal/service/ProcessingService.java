package com.ashford.terminal.service;

import com.ashford.terminal.consistency.ConsistencyChecker;
import com.ashford.terminal.reconcile.Reconciler;
import com.ashford.terminal.reconcile.Staleness;
import com.ashford.terminal.scoring.RiskScoreCalculator;
import com.ashford.terminal.util.Json;
import com.ashford.terminal.validation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates validate -> reconcile -> score -> consistency. Stateless and side-effect free: every call
 * receives the previous dataset from the caller, so concurrent requests cannot interfere with each other and
 * a replayed request produces the same result (apart from timestamps).
 */
public final class ProcessingService {
    public static final int API_VERSION = 1;
    private static final int MAX_SOURCE_CHARS = 1_500_000;
    private static final int MAX_ISSUES_RETURNED = 250;

    private final Clock clock;
    private final DatasetValidator validator = new DatasetValidator();
    private final Reconciler reconciler = new Reconciler();
    private final ConsistencyChecker checker;
    private final Staleness staleness;

    public ProcessingService(Clock clock, Staleness staleness, Set<String> disabledRules) {
        this.clock = clock; this.staleness = staleness; this.checker = new ConsistencyChecker(disabledRules);
    }

    public Staleness staleness() { return staleness; }

    /** POST /api/process */
    public ObjectNode process(JsonNode req) {
        requireObject(req);
        Instant now = clock.instant();
        String runId = shortText(req, "runId", 64);

        JsonNode ext = req.get("extraction");
        String engine = shortText(ext, "engine", 20).replaceAll("[^A-Za-z0-9_-]", "");
        if (engine.isEmpty()) engine = "unknown";
        JsonNode sections = ext == null ? null : ext.get("sections");
        JsonNode errs = ext == null ? null : ext.get("errors");
        Reconciler.SectionState macroHalf = Reconciler.SectionState.parse(Json.text(sections, "macro"));
        Reconciler.SectionState eventsHalf = Reconciler.SectionState.parse(Json.text(sections, "events"));

        ObjectNode previous = null;
        JsonNode pr = req.get("previous");
        if (pr != null && !pr.isNull()) {
            previous = DatasetShape.sanitize(pr);
            if (previous == null) throw new ApiException(400, "invalid_request", "\"previous\" must be a dataset object or null.");
        }
        String source = Json.text(req, "sourceText");
        if (source.length() > MAX_SOURCE_CHARS) source = source.substring(0, MAX_SOURCE_CHARS);

        List<Issue> extra = new ArrayList<>();
        Instant sourceAsOf = Json.instant(req.get("sourceAsOf"));
        if (req.hasNonNull("sourceAsOf") && sourceAsOf == null)
            extra.add(Issue.warn("SOURCE_TIME_INVALID", "sourceAsOf", "sourceAsOf was not an ISO-8601 instant; ignored."));
        if (sourceAsOf != null && sourceAsOf.isAfter(now.plus(Duration.ofMinutes(5)))) {
            extra.add(Issue.warn("SOURCE_TIME_IN_FUTURE", "sourceAsOf", "sourceAsOf is in the future; ignored."));
            sourceAsOf = null;
        }

        Validated v = validator.validate(req.get("fresh"), macroHalf == Reconciler.SectionState.OK,
                eventsHalf == Reconciler.SectionState.OK, SourceTracer.of(source));

        Reconciler.Result r = reconciler.reconcile(new Reconciler.Request(v, previous, req.get("previousIntegrity"),
                req.path("previousIsDemo").asBoolean(false), Json.instant(req.get("previousSavedAt")), now, sourceAsOf, engine,
                macroHalf, eventsHalf, Json.text(errs, "macro"), Json.text(errs, "events"), runId, staleness));

        RiskScoreCalculator.Result score = RiskScoreCalculator.compute(r.dataset().get("bias"));
        List<ConsistencyChecker.Flag> flags = checker.check(r.dataset(), score, (ObjectNode) r.integrity().get("fields"), previous);

        List<Issue> issues = new ArrayList<>(v.issues);
        issues.addAll(r.issues());
        issues.addAll(extra);

        ObjectNode out = Json.obj();
        out.put("apiVersion", API_VERSION);
        out.put("runId", runId);
        out.put("processedAt", now.toString());
        out.set("dataset", r.dataset());
        out.set("integrity", r.integrity());
        out.set("score", score.toJson());
        out.set("flags", flagsJson(flags));
        writeIssues(out, issues);
        out.put("foundCount", r.foundCount());
        out.put("appliedCount", r.appliedCount());
        return out;
    }

    /** POST /api/reevaluate : re-age provenance of an already-loaded dataset; values are never changed. */
    public ObjectNode reevaluate(JsonNode req) {
        requireObject(req);
        Instant now = clock.instant();
        if (req.path("isDemo").asBoolean(false)) {
            ObjectNode o = Json.obj();
            o.put("apiVersion", API_VERSION); o.put("demo", true); o.put("processedAt", now.toString());
            return o;
        }
        ObjectNode ds = DatasetShape.sanitize(req.get("dataset"));
        if (ds == null) throw new ApiException(400, "invalid_request", "\"dataset\" must be a dataset object.");
        ObjectNode integrity = reconciler.reevaluate(ds, req.get("integrity"), Json.instant(req.get("savedAt")), now, staleness);
        RiskScoreCalculator.Result score = RiskScoreCalculator.compute(ds.get("bias"));
        List<ConsistencyChecker.Flag> flags = checker.check(ds, score, (ObjectNode) integrity.get("fields"), null);
        ObjectNode out = Json.obj();
        out.put("apiVersion", API_VERSION);
        out.put("processedAt", now.toString());
        out.set("integrity", integrity);
        out.set("score", score.toJson());
        out.set("flags", flagsJson(flags));
        return out;
    }

    /**
     * POST /api/score : the legacy formula applied to the supplied bias object as-is (no validation), so it can be
     * compared 1:1 with the browser's computeRiskScore(). Out-of-range confidences are reported, not altered.
     */
    public ObjectNode score(JsonNode req) {
        requireObject(req);
        JsonNode bias = req.get("bias");
        if (bias == null || !bias.isObject()) throw new ApiException(400, "invalid_request", "\"bias\" must be an object keyed by asset id.");
        ObjectNode out = Json.obj();
        out.put("apiVersion", API_VERSION);
        out.set("score", RiskScoreCalculator.compute(bias).toJson());
        ArrayNode warnings = out.putArray("warnings");
        bias.fields().forEachRemaining(e -> {
            JsonNode cf = e.getValue().get("cf");
            if (cf != null && cf.isNumber() && (cf.asDouble() < 0 || cf.asDouble() > 100))
                warnings.add("Confidence for " + e.getKey() + " is outside 0-100 and was used as-is (legacy behaviour).");
        });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireObject(JsonNode req) {
        if (req == null || !req.isObject()) throw new ApiException(400, "invalid_request", "Request body must be a JSON object.");
    }

    private static String shortText(JsonNode n, String f, int max) {
        String s = Json.text(n, f).trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static ArrayNode flagsJson(List<ConsistencyChecker.Flag> flags) {
        ArrayNode a = Json.arr();
        flags.forEach(f -> a.add(f.toJson()));
        return a;
    }

    private static void writeIssues(ObjectNode out, List<Issue> issues) {
        int err = 0, warn = 0, info = 0;
        for (Issue i : issues) { switch (i.severity()) { case "error" -> err++; case "warn" -> warn++; default -> info++; } }
        ArrayNode arr = out.putArray("issues");
        // errors first, then warnings, then info, so truncation never hides the important ones
        for (String sev : new String[]{"error", "warn", "info"})
            for (Issue i : issues) if (i.severity().equals(sev) && arr.size() < MAX_ISSUES_RETURNED) arr.add(i.toJson());
        ObjectNode c = out.putObject("issueCounts");
        c.put("error", err); c.put("warn", warn); c.put("info", info);
        out.put("issuesTruncated", issues.size() > MAX_ISSUES_RETURNED);
    }
}
