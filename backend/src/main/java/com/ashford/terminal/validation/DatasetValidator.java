package com.ashford.terminal.validation;

import com.ashford.terminal.schema.Schema;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Function;

/**
 * Validates and normalises a raw AI/local extraction into the dashboard's schema.
 *
 * Principles: (1) never fabricate - an unusable value becomes "" / null, never a neutral default;
 * (2) never overwrite good data with placeholders ("N/A"); (3) reject only what is demonstrably wrong
 * (bad enum, non-numeric reading, wrong type) and merely warn about what is merely suspicious
 * (range, untraceable source, missing source reference).
 */
public final class DatasetValidator {

    private static final Map<String, double[]> RANGE = Map.ofEntries(
            Map.entry("fed", new double[]{0, 25}), Map.entry("cpiH", new double[]{-20, 100}),
            Map.entry("cpiC", new double[]{-20, 100}), Map.entry("ppiH", new double[]{-30, 150}),
            Map.entry("ppiC", new double[]{-30, 150}), Map.entry("real", new double[]{-15, 25}),
            Map.entry("y2", new double[]{-2, 30}), Map.entry("y10", new double[]{-2, 30}),
            Map.entry("dxy", new double[]{40, 200}), Map.entry("wti", new double[]{-50, 400}),
            Map.entry("brent", new double[]{0, 400}), Map.entry("vix", new double[]{0, 200}));

    /**
     * @param raw           the extraction exactly as produced by Groq or the local parser (untrusted)
     * @param macroHalfRan  false when the macro/bias/positioning extraction failed or did not run
     * @param eventsHalfRan false when the events/narratives/summary extraction failed or did not run
     * @param tracer        source text checker, or null to skip traceability checks
     */
    public Validated validate(JsonNode raw, boolean macroHalfRan, boolean eventsHalfRan, SourceTracer tracer) {
        Validated v = new Validated();
        boolean rawOk = raw != null && raw.isObject();
        if (!rawOk && (macroHalfRan || eventsHalfRan)) {
            v.issues.add(Issue.error("RAW_NOT_OBJECT", "$", "Extraction result was not a JSON object; nothing could be used."));
        }
        if (tracer == null && rawOk) {
            v.issues.add(Issue.info("TRACE_SKIPPED", "$", "No source text supplied; value/excerpt traceability checks were skipped."));
        }
        JsonNode r = rawOk ? raw : Json.obj();

        if (macroHalfRan && rawOk) validateMacroHalf(r, v, tracer);
        if (eventsHalfRan && rawOk) validateEventsHalf(r, v, tracer);
        return v;
    }

    // ------------------------------------------------------------------ macro half

    private void validateMacroHalf(JsonNode r, Validated v, SourceTracer tracer) {
        for (String k : Schema.MACRO_HALF_KEYS) {
            JsonNode n = r.get(k);
            if (n == null || n.isNull() || !(n.isObject() || (k.equals("pos") && n.isArray()))) {
                v.issues.add(Issue.warn("REQUIRED_SECTION_MISSING", k, "Section \"" + k + "\" was missing or not an object."));
            }
        }

        // meta
        JsonNode meta = r.get("meta");
        for (String k : List.of("source", "session", "headline")) {
            List<Issue> local = new ArrayList<>();
            Texts.Str s = Texts.clean(meta != null && meta.isObject() ? meta.get(k) : null, "meta." + k, k.equals("headline") ? 400 : 200, local);
            v.issues.addAll(local);
            v.meta.put(k, s.badType() ? Validated.Entry.invalid(local)
                    : s.value().isEmpty() ? Validated.Entry.absent(local) : Validated.Entry.valid(s.value(), local));
        }

        // macro
        JsonNode macro = r.get("macro");
        List<String> notProvided = new ArrayList<>();
        for (Schema.Def d : Schema.MACRO) {
            JsonNode n = macro != null && macro.isObject() ? macro.get(d.id()) : null;
            if (n == null || n.isNull()) notProvided.add(d.id());
            List<Issue> local = new ArrayList<>();
            Validated.Entry<ObjectNode> e = validateMacro(d.id(), n, local, tracer);
            v.issues.addAll(local);
            v.macro.put(d.id(), e);
        }
        if (!notProvided.isEmpty() && macro != null && macro.isObject())
            v.issues.add(Issue.info("ENTRIES_NOT_PROVIDED", "macro", "No entry returned for: " + String.join(", ", notProvided)));

        // bias + matrix
        JsonNode bias = r.get("bias"), matrix = r.get("matrix");
        notProvided = new ArrayList<>();
        for (Schema.Def d : Schema.ASSETS) {
            JsonNode b = bias != null && bias.isObject() ? bias.get(d.id()) : null;
            JsonNode m = matrix != null && matrix.isObject() ? matrix.get(d.id()) : null;
            if (b == null || b.isNull()) notProvided.add(d.id());
            List<Issue> local = new ArrayList<>();
            Validated.Entry<Validated.BiasPair> e = validateBias(d.id(), b, m, local, tracer);
            v.issues.addAll(local);
            v.bias.put(d.id(), e);
        }
        if (!notProvided.isEmpty() && bias != null && bias.isObject())
            v.issues.add(Issue.info("ENTRIES_NOT_PROVIDED", "bias", "No entry returned for: " + String.join(", ", notProvided)));

        // positioning (object keyed by id from the models; array of {id,...} accepted too)
        JsonNode pos = r.get("pos");
        for (Schema.Def d : Schema.POS) {
            List<Issue> local = new ArrayList<>();
            Validated.Entry<ObjectNode> e = validatePos(d, findPos(pos, d.id()), local, tracer);
            v.issues.addAll(local);
            v.pos.put(d.id(), e);
        }
    }

    private static JsonNode findPos(JsonNode pos, String id) {
        if (pos == null) return null;
        if (pos.isObject()) return pos.get(id);
        if (pos.isArray()) for (JsonNode n : pos) if (n != null && id.equals(Json.text(n, "id"))) return n;
        return null;
    }

    private Validated.Entry<ObjectNode> validateMacro(String id, JsonNode n, List<Issue> local, SourceTracer tracer) {
        String path = "macro." + id;
        if (n == null || n.isNull() || n.isMissingNode()) return Validated.Entry.absent(local);
        if (!n.isObject()) {
            local.add(Issue.warn("INVALID_TYPE", path, "Expected an object."));
            return Validated.Entry.invalid(local);
        }
        Texts.Str cur = Texts.clean(n.get("cur"), path + ".cur", 80, local);
        if (cur.badType()) return Validated.Entry.invalid(local);
        if (cur.value().isEmpty()) return Validated.Entry.absent(local);
        if (!Readings.hasDigit(cur.value())) {
            local.add(Issue.error("UNPARSEABLE_READING", path + ".cur",
                    "\"" + cur.value() + "\" contains no number, so it is not a usable reading; rejected."));
            return Validated.Entry.invalid(local);
        }
        String prev = numericOrEmpty(n.get("prev"), path + ".prev", "UNPARSEABLE_PREVIOUS", local);
        String cons = numericOrEmpty(n.get("cons"), path + ".cons", "UNPARSEABLE_CONSENSUS", local);

        String d = "na";
        JsonNode dn = n.get("d");
        if (dn != null && dn.isTextual() && !dn.asText().isBlank()) {
            String canon = Schema.canonical(Schema.TRENDS, dn.asText());
            if (canon == null) local.add(Issue.info("INVALID_TREND", path + ".d", "Unknown trend \"" + dn.asText() + "\"; recorded as na."));
            else d = canon;
        }
        String note = Texts.clean(n.get("note"), path + ".note", 200, local).value();
        String src = Texts.clean(n.get("src"), path + ".src", 600, local).value();

        double[] range = RANGE.get(id);
        OptionalDouble num = Readings.first(cur.value());
        if (range != null && num.isPresent() && !Readings.isBasisPoints(cur.value()) && !Readings.hasMagnitudeSuffix(cur.value())
                && (num.getAsDouble() < range[0] || num.getAsDouble() > range[1])) {
            local.add(Issue.warn("IMPLAUSIBLE_RANGE", path + ".cur",
                    "Reading " + cur.value() + " is outside the plausible range " + range[0] + " to " + range[1] + "; kept but flagged."));
        }
        if (tracer != null && !tracer.readingNumberPresent(cur.value())) {
            local.add(Issue.warn("VALUE_NOT_FOUND_IN_SOURCE", path + ".cur",
                    "The number in \"" + cur.value() + "\" was not found in the analysed source text."));
        }
        checkSource(src, path + ".src", true, local, tracer);

        ObjectNode o = Json.obj();
        o.put("cur", cur.value()); o.put("prev", prev); o.put("cons", cons);
        o.put("d", d); o.put("note", note); o.put("src", src);
        return Validated.Entry.valid(o, local);
    }

    private static String numericOrEmpty(JsonNode n, String path, String code, List<Issue> local) {
        String s = Texts.clean(n, path, 80, local).value();
        if (!s.isEmpty() && !Readings.hasDigit(s)) {
            local.add(Issue.warn(code, path, "\"" + s + "\" contains no number; dropped."));
            return "";
        }
        return s;
    }

    private Validated.Entry<Validated.BiasPair> validateBias(String id, JsonNode b, JsonNode m, List<Issue> local, SourceTracer tracer) {
        String path = "bias." + id;
        if (b == null || b.isNull() || b.isMissingNode()) return Validated.Entry.absent(local);
        if (!b.isObject()) {
            local.add(Issue.warn("INVALID_TYPE", path, "Expected an object."));
            return Validated.Entry.invalid(local);
        }
        Texts.Str label = Texts.clean(b.get("b"), path + ".b", 30, local);
        if (label.badType()) return Validated.Entry.invalid(local);
        if (label.value().isEmpty()) return Validated.Entry.absent(local);
        String canon = Schema.canonical(Schema.BIAS_LABELS, label.value());
        if (canon == null) {
            local.add(Issue.error("INVALID_BIAS_LABEL", path + ".b",
                    "\"" + label.value() + "\" is not one of Bullish / Bearish / Neutral; entry rejected."));
            return Validated.Entry.invalid(local);
        }
        Double cf = Texts.score0to100(b.get("cf"), path + ".cf", local);
        String src = Texts.clean(b.get("src"), path + ".src", 600, local).value();

        ObjectNode bias = Json.obj();
        bias.put("b", canon);
        putScore(bias, "cf", cf);
        bias.put("pd", Texts.clean(b.get("pd"), path + ".pd", 160, local).value());
        bias.put("br", Texts.clean(b.get("br"), path + ".br", 160, local).value());
        bias.put("nc", Texts.clean(b.get("nc"), path + ".nc", 160, local).value());
        bias.put("src", src);

        String mp = "matrix." + id;
        ObjectNode matrix = Json.obj();
        if (m != null && m.isObject()) {
            matrix.put("bd", Texts.clean(m.get("bd"), mp + ".bd", 240, local).value());
            matrix.put("brd", Texts.clean(m.get("brd"), mp + ".brd", 240, local).value());
            matrix.put("cd", Texts.clean(m.get("cd"), mp + ".cd", 120, local).value());
            String ml = Texts.clean(m.get("b"), mp + ".b", 30, local).value();
            String mcanon = ml.isEmpty() ? "" : Schema.canonical(Schema.BIAS_LABELS, ml);
            if (!ml.isEmpty() && mcanon == null) {
                local.add(Issue.warn("MATRIX_INVALID_LABEL", mp + ".b", "\"" + ml + "\" is not a valid label; matrix bias left empty."));
                mcanon = "";
            }
            matrix.put("b", mcanon == null ? "" : mcanon);
            putScore(matrix, "cf", Texts.score0to100(m.get("cf"), mp + ".cf", local));
            matrix.put("src", Texts.clean(m.get("src"), mp + ".src", 600, local).value());
        } else {
            local.add(Issue.info("MATRIX_MISSING", mp, "No driver-matrix entry returned alongside this bias."));
            matrix.put("bd", ""); matrix.put("brd", ""); matrix.put("cd", ""); matrix.put("b", "");
            matrix.putNull("cf"); matrix.put("src", "");
        }
        checkSource(src, path + ".src", true, local, tracer);
        return Validated.Entry.valid(new Validated.BiasPair(bias, matrix), local);
    }

    private Validated.Entry<ObjectNode> validatePos(Schema.Def def, JsonNode n, List<Issue> local, SourceTracer tracer) {
        String path = "pos." + def.id();
        if (n == null || n.isNull() || n.isMissingNode()) return Validated.Entry.absent(local);
        if (!n.isObject()) {
            local.add(Issue.warn("INVALID_TYPE", path, "Expected an object."));
            return Validated.Entry.invalid(local);
        }
        Texts.Str val = Texts.clean(n.get("v"), path + ".v", 60, local);
        if (val.badType()) return Validated.Entry.invalid(local);
        if (val.value().isEmpty()) return Validated.Entry.absent(local);
        String tone = "none";
        JsonNode tn = n.get("tone");
        if (tn != null && tn.isTextual() && !tn.asText().isBlank()) {
            String c = Schema.canonical(Schema.POS_TONES, tn.asText());
            if (c == null) local.add(Issue.warn("INVALID_TONE", path + ".tone", "Unknown tone \"" + tn.asText() + "\"; recorded as none."));
            else tone = c;
        }
        String src = Texts.clean(n.get("src"), path + ".src", 600, local).value();
        checkSource(src, path + ".src", true, local, tracer);
        ObjectNode o = Json.obj();
        o.put("id", def.id()); o.put("label", def.label()); o.put("v", val.value()); o.put("tone", tone); o.put("src", src);
        return Validated.Entry.valid(o, local);
    }

    // ------------------------------------------------------------------ events half

    private void validateEventsHalf(JsonNode r, Validated v, SourceTracer tracer) {
        for (String k : Schema.EVENTS_HALF_KEYS) {
            JsonNode n = r.get(k);
            if (n == null || n.isNull() || !(n.isArray() || (k.equals("summary") && n.isObject()))) {
                v.issues.add(Issue.warn("REQUIRED_SECTION_MISSING", k, "Section \"" + k + "\" was missing or had the wrong type."));
            }
        }
        v.events = list(r.get("events"), "events", Schema.EVENTS_MAX, v, tracer, (n, p, local) -> {
            String h = Texts.clean(n.get("h"), p + ".h", 300, local).value();
            if (h.isEmpty()) return null;
            ObjectNode o = Json.obj();
            o.put("cat", Texts.clean(n.get("cat"), p + ".cat", 40, local).value());
            o.put("t", Texts.clean(n.get("t"), p + ".t", 40, local).value());
            o.put("dt", Texts.clean(n.get("dt"), p + ".dt", 60, local).value());
            o.put("h", h);
            o.put("w", Texts.clean(n.get("w"), p + ".w", 400, local).value());
            o.put("aa", assets(n.get("aa"), p + ".aa", local));
            o.put("imp", importance(n.get("imp"), p + ".imp", local));
            String src = Texts.clean(n.get("src"), p + ".src", 600, local).value();
            o.put("src", src);
            checkSource(src, p + ".src", true, local, tracer);
            return o;
        }, o -> o.get("h").asText());

        v.narratives = list(r.get("narratives"), "narratives", Schema.NARRATIVES_MAX, v, tracer, (n, p, local) -> {
            String name = Texts.clean(n.get("n"), p + ".n", 120, local).value();
            if (name.isEmpty()) return null;
            ObjectNode o = Json.obj();
            o.put("n", name);
            String tone = "none";
            JsonNode tn = n.get("tone");
            if (tn != null && tn.isTextual() && !tn.asText().isBlank()) {
                String c = Schema.canonical(Schema.NARRATIVE_TONES, tn.asText());
                if (c == null) local.add(Issue.warn("INVALID_TONE", p + ".tone", "Unknown tone \"" + tn.asText() + "\"; recorded as none."));
                else tone = c;
            } else local.add(Issue.info("MISSING_TONE", p + ".tone", "No tone supplied; recorded as none."));
            o.put("tone", tone);
            putScore(o, "s", Texts.score0to100(n.get("s"), p + ".s", local));
            o.put("aa", assets(n.get("aa"), p + ".aa", local));
            String src = Texts.clean(n.get("src"), p + ".src", 600, local).value();
            o.put("src", src);
            checkSource(src, p + ".src", true, local, tracer);
            return o;
        }, o -> o.get("n").asText());

        v.watchlist = list(r.get("watchlist"), "watchlist", Schema.WATCHLIST_MAX, v, tracer, (n, p, local) -> {
            String e = Texts.clean(n.get("e"), p + ".e", 300, local).value();
            if (e.isEmpty()) return null;
            ObjectNode o = Json.obj();
            o.put("t", Texts.clean(n.get("t"), p + ".t", 40, local).value());
            o.put("dt", Texts.clean(n.get("dt"), p + ".dt", 60, local).value());
            o.put("e", e);
            o.put("f", Texts.clean(n.get("f"), p + ".f", 80, local).value());
            o.put("imp", importance(n.get("imp"), p + ".imp", local));
            o.put("aa", assets(n.get("aa"), p + ".aa", local));
            String src = Texts.clean(n.get("src"), p + ".src", 600, local).value();
            o.put("src", src);
            checkSource(src, p + ".src", true, local, tracer);
            return o;
        }, o -> o.get("e").asText() + "|" + o.get("dt").asText());

        v.today = list(r.get("today"), "today", Schema.TODAY_MAX, v, tracer, (n, p, local) -> {
            String title = Texts.clean(n.get("title"), p + ".title", 300, local).value();
            if (title.isEmpty()) return null;
            ObjectNode o = Json.obj();
            o.put("cat", Texts.clean(n.get("cat"), p + ".cat", 40, local).value());
            o.put("t", Texts.clean(n.get("t"), p + ".t", 40, local).value());
            o.put("title", title);
            o.put("aa", assets(n.get("aa"), p + ".aa", local));
            o.put("up", Texts.clean(n.get("up"), p + ".up", 500, local).value());
            o.put("down", Texts.clean(n.get("down"), p + ".down", 500, local).value());
            String src = Texts.clean(n.get("src"), p + ".src", 600, local).value();
            o.put("src", src);
            checkSource(src, p + ".src", true, local, tracer);
            return o;
        }, o -> o.get("title").asText());

        // summary bullets
        JsonNode sum = r.get("summary");
        JsonNode bl = sum != null && sum.isObject() ? sum.get("bullets") : null;
        List<Issue> local = new ArrayList<>();
        if (bl == null || bl.isNull()) {
            v.bullets = Validated.Entry.absent(local);
        } else if (!bl.isArray()) {
            local.add(Issue.warn("INVALID_TYPE", "summary.bullets", "Expected an array of strings."));
            v.bullets = Validated.Entry.invalid(local);
        } else {
            ArrayNode out = Json.arr();
            Set<String> seen = new HashSet<>();
            int i = 0;
            for (JsonNode b : bl) {
                String p = "summary.bullets[" + i++ + "]";
                if (!b.isTextual()) { local.add(Issue.warn("ITEM_DROPPED", p, "Bullet was not a string; dropped.")); continue; }
                String s = Texts.clean(b, p, 600, local).value();
                if (s.isEmpty() || !seen.add(s.toLowerCase(Locale.ROOT))) continue;
                if (out.size() >= Schema.BULLETS_MAX) { local.add(Issue.info("TRUNCATED_LIST", "summary.bullets", "More than " + Schema.BULLETS_MAX + " bullets; extras ignored.")); break; }
                out.add(s);
            }
            v.bullets = out.isEmpty() ? (bl.isEmpty() ? Validated.Entry.absent(local) : Validated.Entry.invalid(local))
                                      : Validated.Entry.valid(out, local);
        }
        v.issues.addAll(local);
    }

    private interface ItemValidator { ObjectNode validate(JsonNode item, String path, List<Issue> local); }

    private Validated.Entry<ArrayNode> list(JsonNode raw, String key, int max, Validated v, SourceTracer tracer,
                                            ItemValidator iv, Function<ObjectNode, String> dedupeKey) {
        List<Issue> local = new ArrayList<>();
        if (raw == null || raw.isNull()) return Validated.Entry.absent(local);
        if (!raw.isArray()) {
            local.add(Issue.warn("INVALID_TYPE", key, "Expected an array."));
            v.issues.addAll(local);
            return Validated.Entry.invalid(local);
        }
        ArrayNode out = Json.arr();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode item : raw) {
            String p = key + "[" + i++ + "]";
            if (!item.isObject()) { local.add(Issue.warn("ITEM_DROPPED", p, "Item was not an object; dropped.")); continue; }
            ObjectNode o = iv.validate(item, p, local);
            if (o == null) { local.add(Issue.warn("ITEM_DROPPED", p, "Item is missing its required headline/name field; dropped.")); continue; }
            if (!seen.add(dedupeKey.apply(o).toLowerCase(Locale.ROOT))) { local.add(Issue.info("DUPLICATE_ITEM", p, "Duplicate of an earlier item; dropped.")); continue; }
            if (out.size() >= max) { local.add(Issue.info("TRUNCATED_LIST", key, "More than " + max + " items; extras ignored.")); break; }
            out.add(o);
        }
        v.issues.addAll(local);
        if (!out.isEmpty()) return Validated.Entry.valid(out, local);
        return raw.isEmpty() ? Validated.Entry.absent(local) : Validated.Entry.invalid(local);
    }

    // ------------------------------------------------------------------ shared helpers

    private static void putScore(ObjectNode o, String field, Double v) {
        if (v == null) o.putNull(field); else o.set(field, Json.num(v));
    }

    private static String importance(JsonNode n, String path, List<Issue> local) {
        if (n == null || n.isNull()) return "";
        String s = n.isTextual() ? n.asText().trim() : "";
        if (s.isEmpty()) return "";
        String c = Schema.canonical(Schema.IMPORTANCE, s);
        if (c == null) { local.add(Issue.info("INVALID_IMPORTANCE", path, "\"" + s + "\" is not High/Medium/Low; left unset.")); return ""; }
        return c;
    }

    private static String assets(JsonNode n, String path, List<Issue> local) {
        String s = Texts.clean(n, path, 160, local).value();
        if (s.isEmpty()) return s;
        for (String tok : s.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty() && Schema.canonical(Schema.KNOWN_ASSET_TOKENS, t) == null)
                local.add(Issue.info("UNKNOWN_ASSET_REF", path, "\"" + t + "\" is not one of the terminal's tracked assets."));
        }
        return s;
    }

    private static void checkSource(String src, String path, boolean valuePresent, List<Issue> local, SourceTracer tracer) {
        if (valuePresent && src.isEmpty()) {
            local.add(Issue.warn("MISSING_SOURCE_REFERENCE", path, "No supporting source excerpt was supplied for this value."));
        } else if (!src.isEmpty() && tracer != null && !tracer.excerptTraceable(src)) {
            local.add(Issue.warn("SOURCE_NOT_TRACEABLE", path, "The source excerpt could not be matched to the analysed text."));
        }
    }
}
