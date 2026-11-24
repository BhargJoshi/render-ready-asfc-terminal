package com.ashford.terminal.validation;

import com.ashford.terminal.schema.Schema;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * Canonical dataset skeleton + a lenient sanitiser for datasets that arrive from the browser
 * (persisted or previously returned by this service). It guarantees structure and types only; it does
 * not re-run semantic validation and it never invents values.
 */
public final class DatasetShape {
    private DatasetShape() {}

    public static ObjectNode emptyMacro() {
        ObjectNode o = Json.obj();
        o.put("cur", ""); o.put("prev", ""); o.put("cons", ""); o.put("d", "na"); o.put("note", ""); o.put("src", "");
        return o;
    }
    public static ObjectNode emptyBias() {
        ObjectNode o = Json.obj();
        o.put("b", ""); o.putNull("cf"); o.put("pd", ""); o.put("br", ""); o.put("nc", ""); o.put("src", "");
        return o;
    }
    public static ObjectNode emptyMatrix() {
        ObjectNode o = Json.obj();
        o.put("bd", ""); o.put("brd", ""); o.put("cd", ""); o.put("b", ""); o.putNull("cf"); o.put("src", "");
        return o;
    }
    public static ObjectNode emptyPos(Schema.Def d) {
        ObjectNode o = Json.obj();
        o.put("id", d.id()); o.put("label", d.label()); o.put("v", ""); o.put("tone", "none"); o.put("src", "");
        return o;
    }

    public static ObjectNode empty() {
        ObjectNode ds = Json.obj();
        ObjectNode meta = ds.putObject("meta");
        meta.put("source", ""); meta.put("session", ""); meta.put("headline", "");
        ObjectNode macro = ds.putObject("macro");
        for (Schema.Def d : Schema.MACRO) macro.set(d.id(), emptyMacro());
        ObjectNode bias = ds.putObject("bias");
        for (Schema.Def d : Schema.ASSETS) bias.set(d.id(), emptyBias());
        ObjectNode matrix = ds.putObject("matrix");
        for (Schema.Def d : Schema.ASSETS) matrix.set(d.id(), emptyMatrix());
        ArrayNode pos = ds.putArray("pos");
        for (Schema.Def d : Schema.POS) pos.add(emptyPos(d));
        ds.putArray("events"); ds.putArray("narratives"); ds.putArray("watchlist"); ds.putArray("today");
        ds.putObject("summary").putArray("bullets");
        return ds;
    }

    /** Returns null if the input is not an object (caller decides how to treat that). */
    public static ObjectNode sanitize(JsonNode raw) {
        if (raw == null || !raw.isObject()) return null;
        ObjectNode ds = empty();
        JsonNode meta = raw.get("meta");
        for (String k : new String[]{"source", "session", "headline"})
            ((ObjectNode) ds.get("meta")).put(k, str(meta, k, 400));

        JsonNode macro = raw.get("macro");
        for (Schema.Def d : Schema.MACRO) {
            JsonNode n = macro == null ? null : macro.get(d.id());
            ObjectNode o = emptyMacro();
            if (n != null && n.isObject()) {
                o.put("cur", str(n, "cur", 80)); o.put("prev", str(n, "prev", 80)); o.put("cons", str(n, "cons", 80));
                String t = Schema.canonical(Schema.TRENDS, str(n, "d", 10));
                o.put("d", t == null ? "na" : t);
                o.put("note", str(n, "note", 200)); o.put("src", str(n, "src", 600));
            }
            ((ObjectNode) ds.get("macro")).set(d.id(), o);
        }
        JsonNode bias = raw.get("bias"), matrix = raw.get("matrix");
        for (Schema.Def d : Schema.ASSETS) {
            JsonNode b = bias == null ? null : bias.get(d.id());
            ObjectNode bo = emptyBias();
            if (b != null && b.isObject()) {
                String lab = Schema.canonical(Schema.BIAS_LABELS, str(b, "b", 30));
                bo.put("b", lab == null ? "" : lab);
                putCf(bo, b.get("cf"));
                bo.put("pd", str(b, "pd", 160)); bo.put("br", str(b, "br", 160)); bo.put("nc", str(b, "nc", 160)); bo.put("src", str(b, "src", 600));
            }
            ((ObjectNode) ds.get("bias")).set(d.id(), bo);
            JsonNode m = matrix == null ? null : matrix.get(d.id());
            ObjectNode mo = emptyMatrix();
            if (m != null && m.isObject()) {
                mo.put("bd", str(m, "bd", 240)); mo.put("brd", str(m, "brd", 240)); mo.put("cd", str(m, "cd", 120));
                String ml = Schema.canonical(Schema.BIAS_LABELS, str(m, "b", 30));
                mo.put("b", ml == null ? "" : ml);
                putCf(mo, m.get("cf"));
                mo.put("src", str(m, "src", 600));
            }
            ((ObjectNode) ds.get("matrix")).set(d.id(), mo);
        }
        JsonNode pos = raw.get("pos");
        ArrayNode posOut = (ArrayNode) ds.get("pos");
        for (int i = 0; i < Schema.POS.size(); i++) {
            Schema.Def d = Schema.POS.get(i);
            JsonNode n = null;
            if (pos != null && pos.isArray()) for (JsonNode x : pos) if (d.id().equals(Json.text(x, "id"))) n = x;
            if (pos != null && pos.isObject()) n = pos.get(d.id());
            ObjectNode o = emptyPos(d);
            if (n != null && n.isObject()) {
                o.put("v", str(n, "v", 60));
                String tone = Schema.canonical(Schema.POS_TONES, str(n, "tone", 20));
                o.put("tone", tone == null ? "none" : tone);
                o.put("src", str(n, "src", 600));
            }
            posOut.set(i, o);
        }
        copyList(raw.get("events"), (ArrayNode) ds.get("events"), Schema.EVENTS_MAX, "h",
                new String[]{"cat", "t", "dt", "h", "w", "aa", "imp", "src"});
        copyList(raw.get("watchlist"), (ArrayNode) ds.get("watchlist"), Schema.WATCHLIST_MAX, "e",
                new String[]{"t", "dt", "e", "f", "imp", "aa", "src"});
        copyList(raw.get("today"), (ArrayNode) ds.get("today"), Schema.TODAY_MAX, "title",
                new String[]{"cat", "t", "title", "aa", "up", "down", "src"});
        JsonNode nar = raw.get("narratives");
        if (nar != null && nar.isArray()) {
            for (JsonNode n : nar) {
                if (!n.isObject() || str(n, "n", 120).isEmpty() || ((ArrayNode) ds.get("narratives")).size() >= Schema.NARRATIVES_MAX) continue;
                ObjectNode o = Json.obj();
                o.put("n", str(n, "n", 120));
                String tone = Schema.canonical(Schema.NARRATIVE_TONES, str(n, "tone", 20));
                o.put("tone", tone == null ? "none" : tone);
                JsonNode s = n.get("s");
                if (s != null && s.isNumber() && s.asDouble() >= 0 && s.asDouble() <= 100) o.set("s", Json.num(s.asDouble())); else o.putNull("s");
                o.put("aa", str(n, "aa", 160)); o.put("src", str(n, "src", 600));
                ((ArrayNode) ds.get("narratives")).add(o);
            }
        }
        JsonNode bl = raw.get("summary") == null ? null : raw.get("summary").get("bullets");
        if (bl != null && bl.isArray()) {
            ArrayNode out = (ArrayNode) ds.get("summary").get("bullets");
            for (JsonNode b : bl) if (b.isTextual() && !b.asText().isBlank() && out.size() < Schema.BULLETS_MAX) out.add(b.asText().trim());
        }
        return ds;
    }

    private static void copyList(JsonNode src, ArrayNode out, int max, String required, String[] fields) {
        if (src == null || !src.isArray()) return;
        for (JsonNode n : src) {
            if (!n.isObject() || str(n, required, 400).isEmpty() || out.size() >= max) continue;
            ObjectNode o = Json.obj();
            for (String f : fields) {
                String val = str(n, f, 600);
                if (f.equals("imp")) { String c = Schema.canonical(Schema.IMPORTANCE, val); val = c == null ? "" : c; }
                o.put(f, val);
            }
            out.add(o);
        }
    }

    private static void putCf(ObjectNode o, JsonNode cf) {
        if (cf != null && cf.isNumber() && !Double.isNaN(cf.asDouble()) && cf.asDouble() >= 0 && cf.asDouble() <= 100)
            o.set("cf", Json.num(cf.asDouble()));
        else o.putNull("cf");
    }

    private static String str(JsonNode n, String f, int max) {
        if (n == null || !n.isObject()) return "";
        JsonNode v = n.get(f);
        if (v == null || !v.isTextual()) return "";
        String s = v.asText().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
