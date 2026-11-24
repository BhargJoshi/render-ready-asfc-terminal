package com.ashford.terminal.consistency;

import com.ashford.terminal.scoring.RiskScoreCalculator;
import com.ashford.terminal.util.Json;
import com.ashford.terminal.validation.Readings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Rule-based contradiction flags. They NEVER modify the dataset: AI-generated interpretations are left as they
 * are and the reader is told where two parts of the dashboard may disagree.
 *
 * Two kinds of rule, kept separate on purpose:
 *  - structural rules (heuristic=false): two fields of the same dataset directly disagree (e.g. bias label vs matrix label);
 *  - relationship rules (heuristic=true): a conventional market relationship that is NOT universal (bond price vs yield
 *    horizon, gold vs real rates, ...). They are "info" severity and carry an explicit assumption string.
 * Any rule can be switched off by id (CONSISTENCY_DISABLED_RULES) without code changes.
 */
public final class ConsistencyChecker {

    public record Flag(String id, String severity, String scope, List<String> refs, String message,
                       String assumption, boolean heuristic) {
        public ObjectNode toJson() {
            ObjectNode o = Json.obj();
            o.put("id", id); o.put("severity", severity); o.put("scope", scope);
            ArrayNode r = o.putArray("refs"); refs.forEach(r::add);
            o.put("message", message);
            if (assumption != null) o.put("assumption", assumption);
            o.put("heuristic", heuristic);
            return o;
        }
    }

    private static final Pattern UP = Pattern.compile("\\b(rose|rising|rises|higher|increas\\w*|climb\\w*|jump\\w*|surg\\w*|accelerat\\w*|hotter|stronger|gain\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWN = Pattern.compile("\\b(fell|falling|falls|lower|declin\\w*|decreas\\w*|eas(?:ed|es|ing)|cool\\w*|slow\\w*|drop\\w*|weaker|softer|slid\\w*)\\b", Pattern.CASE_INSENSITIVE);

    private final Set<String> disabled;

    public ConsistencyChecker(Set<String> disabledRuleIds) { this.disabled = disabledRuleIds == null ? Set.of() : disabledRuleIds; }

    public List<Flag> check(ObjectNode ds, RiskScoreCalculator.Result score, ObjectNode integrityFields, ObjectNode previous) {
        List<Flag> out = new ArrayList<>();
        JsonNode bias = ds.get("bias"), matrix = ds.get("matrix"), macro = ds.get("macro");
        Map<String, String> posTone = new HashMap<>();
        for (JsonNode p : ds.get("pos")) if (!Json.text(p, "v").isEmpty()) posTone.put(Json.text(p, "id"), Json.text(p, "tone"));

        // ---- structural: bias card vs driver matrix
        for (var e : iter(bias)) {
            String id = e.getKey(), bl = Json.text(e.getValue(), "b"), ml = Json.text(matrix.get(id), "b");
            if (!bl.isEmpty() && !ml.isEmpty() && !bl.equals(ml))
                add(out, new Flag("BIAS_MATRIX_LABEL_MISMATCH", "warn", "bias", List.of("bias." + id, "matrix." + id),
                        label(id) + ": the bias card says " + bl + " but the driver matrix says " + ml + ".", null, false));
            String bd = Json.text(matrix.get(id), "bd"), brd = Json.text(matrix.get(id), "brd");
            if (bl.equals("Bullish") && bd.isEmpty() && !brd.isEmpty())
                add(out, new Flag("BIAS_DRIVER_DIRECTION_GAP", "info", "bias", List.of("bias." + id, "matrix." + id),
                        label(id) + " is Bullish but only bearish drivers are listed.", null, false));
            if (bl.equals("Bearish") && brd.isEmpty() && !bd.isEmpty())
                add(out, new Flag("BIAS_DRIVER_DIRECTION_GAP", "info", "bias", List.of("bias." + id, "matrix." + id),
                        label(id) + " is Bearish but only bullish drivers are listed.", null, false));
            Double cf = cf(e.getValue());
            if (bl.equals("Neutral") && cf != null && cf >= 85)
                add(out, new Flag("HIGH_CONFIDENCE_NEUTRAL", "info", "bias", List.of("bias." + id),
                        label(id) + " is Neutral with " + fmt(cf) + "% confidence; confirm whether that means 'confidently range-bound' or a weak signal.",
                        "Confidence is read as confidence in the stated label.", true));
        }

        // ---- structural: positioning themes vs the asset bias they describe
        posVsBias(out, posTone, "gold", "gold", bias);
        posVsBias(out, posTone, "bond", "usBonds", bias);
        if (score != null && score.value() != null && posTone.containsKey("risk")) {
            String t = posTone.get("risk"); int s = score.value();
            if ((t.equals("bullish") && s <= -15) || (t.equals("bearish") && s >= 15))
                add(out, new Flag("POS_RISK_VS_SCORE", "info", "pos", List.of("pos.risk"),
                        "The Risk On/Off theme reads " + t + " but the composite score is " + s + ".",
                        "A bullish tone on the risk theme is read as risk-on.", true));
        }
        if (posTone.containsKey("dollar")) {
            String t = posTone.get("dollar"), d = Json.text(macro.get("dxy"), "d");
            if (!Json.text(macro.get("dxy"), "cur").isEmpty() && ((t.equals("bullish") && d.equals("down")) || (t.equals("bearish") && d.equals("up"))))
                add(out, new Flag("POS_DOLLAR_VS_DXY", "info", "pos", List.of("pos.dollar", "macro.dxy"),
                        "The Dollar theme reads " + t + " while DXY is trending " + d + ".",
                        "The theme's tone describes the dollar's direction (bullish = stronger dollar).", true));
        }

        // ---- relationship rules (conventional, not universal)
        String bonds = Json.text(bias.get("usBonds"), "b");
        for (String y : List.of("y10", "y2")) {
            String d = Json.text(macro.get(y), "d");
            if (Json.text(macro.get(y), "cur").isEmpty()) continue;
            if ((bonds.equals("Bullish") && d.equals("up")) || (bonds.equals("Bearish") && d.equals("down")))
                add(out, new Flag("BONDS_VS_YIELDS", "info", "bias", List.of("bias.usBonds", "macro." + y),
                        "US Bonds bias is " + bonds + " while the " + (y.equals("y10") ? "10Y" : "2Y") + " yield is trending " + d + ".",
                        "Bond prices normally move opposite to yields. The bias may refer to a different horizon, the curve, or carry; verify the driver text.", true));
        }
        Double goldCf = cf(bias.get("gold"));
        if ("Bullish".equals(Json.text(bias.get("gold"), "b")) && goldCf != null && goldCf >= 70
                && "up".equals(Json.text(macro.get("real"), "d")) && "up".equals(Json.text(macro.get("dxy"), "d")))
            add(out, new Flag("GOLD_VS_REAL_RATES_DOLLAR", "info", "bias", List.of("bias.gold", "macro.real", "macro.dxy"),
                    "Gold is Bullish (" + fmt(goldCf) + "%) while real rates and the dollar are both rising.",
                    "Gold often trades inversely to real rates and the dollar, but not always (e.g. central-bank buying, haven demand); check the stated driver.", true));
        Double eqCf = cf(bias.get("usEq")); OptionalDouble vix = Readings.first(Json.text(macro.get("vix"), "cur"));
        if ("Bullish".equals(Json.text(bias.get("usEq"), "b")) && eqCf != null && eqCf >= 70 && vix.isPresent() && vix.getAsDouble() >= 30)
            add(out, new Flag("EQUITY_BULLISH_IN_HIGH_VOL", "info", "bias", List.of("bias.usEq", "macro.vix"),
                    "US Equities is Bullish (" + fmt(eqCf) + "%) with VIX at " + Json.text(macro.get("vix"), "cur") + ".",
                    "VIX at or above 30 usually signals stress, though rebounds from spikes can be genuinely bullish.", true));

        // ---- macro internal consistency
        for (var e : iter(macro)) {
            String id = e.getKey(); JsonNode m = e.getValue();
            String cur = Json.text(m, "cur"), prev = Json.text(m, "prev"), d = Json.text(m, "d");
            if (cur.isEmpty()) continue;
            if (!prev.isEmpty() && (d.equals("up") || d.equals("down") || d.equals("flat"))
                    && !Readings.isBasisPoints(cur + prev) && !Readings.hasMagnitudeSuffix(cur + prev)) {
                OptionalDouble c = Readings.first(cur), p = Readings.first(prev);
                if (c.isPresent() && p.isPresent()) {
                    double diff = c.getAsDouble() - p.getAsDouble(), base = Math.max(Math.abs(c.getAsDouble()), Math.abs(p.getAsDouble()));
                    boolean bad = (d.equals("up") && diff < 0) || (d.equals("down") && diff > 0) || (d.equals("flat") && base > 0 && Math.abs(diff) / base > 0.005);
                    if (bad) add(out, new Flag("MACRO_TREND_VS_VALUES", "warn", "macro", List.of("macro." + id),
                            label(id) + ": trend is '" + d + "' but latest " + cur + " vs previous " + prev + " says otherwise.",
                            "The trend field is read as direction versus the previous reading.", true));
                }
            }
            String note = Json.text(m, "note");
            if (!note.isEmpty()) {
                boolean up = UP.matcher(note).find(), down = DOWN.matcher(note).find();
                if ((d.equals("down") && up && !down) || (d.equals("up") && down && !up))
                    add(out, new Flag("MACRO_NOTE_VS_TREND", "info", "macro", List.of("macro." + id),
                            label(id) + ": the interpretation text reads as moving " + (up ? "up" : "down") + " but the trend is '" + d + "'.",
                            "Simple wording check on the note; the note may describe something other than this indicator's own direction.", true));
            }
        }

        // ---- changes versus what was loaded before (only for values updated in this run)
        if (previous != null) {
            for (var e : iter(bias)) {
                String id = e.getKey();
                if (!updated(integrityFields, "bias." + id)) continue;
                String nb = Json.text(e.getValue(), "b"), pb = Json.text(previous.get("bias").get(id), "b");
                Double nc = cf(e.getValue()), pc = cf(previous.get("bias").get(id));
                if (!nb.isEmpty() && !pb.isEmpty() && !nb.equals(pb) && !nb.equals("Neutral") && !pb.equals("Neutral")
                        && nc != null && pc != null && nc >= 60 && pc >= 60)
                    add(out, new Flag("BIAS_REVERSAL_VS_PREVIOUS", "info", "bias", List.of("bias." + id),
                            label(id) + " flipped from " + pb + " (" + fmt(pc) + "%) to " + nb + " (" + fmt(nc) + "%) in one update.",
                            "A full reversal in a single update can be real news or a thin/odd source; check the source excerpt.", true));
            }
            for (var e : iter(macro)) {
                String id = e.getKey();
                if (!updated(integrityFields, "macro." + id)) continue;
                OptionalDouble n = Readings.first(Json.text(e.getValue(), "cur")), o = Readings.first(Json.text(previous.get("macro").get(id), "cur"));
                if (n.isPresent() && o.isPresent() && o.getAsDouble() != 0 && Math.abs((n.getAsDouble() - o.getAsDouble()) / o.getAsDouble()) > 0.5)
                    add(out, new Flag("LARGE_CHANGE_VS_PREVIOUS", "info", "macro", List.of("macro." + id),
                            label(id) + " moved from " + Json.text(previous.get("macro").get(id), "cur") + " to " + Json.text(e.getValue(), "cur") + " (over 50%).",
                            "Large moves happen, but they are also what a mis-read figure looks like; check the source excerpt.", true));
            }
        }

        // ---- disclosures about the composite score
        if (score != null && score.value() != null) {
            List<String> nonFresh = new ArrayList<>(), defaulted = new ArrayList<>();
            for (var in : score.inputs()) {
                if (!updated(integrityFields, "bias." + in.asset())) nonFresh.add(in.label());
                if (in.confidenceDefaulted()) defaulted.add(in.label());
            }
            if (integrityFields != null && !nonFresh.isEmpty())
                add(out, new Flag("SCORE_USES_NON_FRESH_INPUTS", "info", "score", nonFresh.stream().map(x -> "bias." + x).toList(),
                        "The composite score includes asset biases that were not refreshed by the latest update: " + String.join(", ", nonFresh) + ".", null, false));
            if (!defaulted.isEmpty())
                add(out, new Flag("SCORE_CONFIDENCE_DEFAULTED", "info", "score", List.of(),
                        "No confidence was available for " + String.join(", ", defaulted) + "; the score formula weights these at 50% (unchanged legacy behaviour).", null, false));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void posVsBias(List<Flag> out, Map<String, String> posTone, String posId, String assetId, JsonNode bias) {
        String t = posTone.get(posId), b = Json.text(bias.get(assetId), "b");
        if (t == null || b.isEmpty()) return;
        if ((t.equals("bullish") && b.equals("Bearish")) || (t.equals("bearish") && b.equals("Bullish")))
            add(out, new Flag("POS_VS_ASSET_BIAS", "warn", "pos", List.of("pos." + posId, "bias." + assetId),
                    "The " + posId + " positioning theme reads " + t + " but the " + label(assetId) + " bias is " + b + ".", null, false));
    }

    private void add(List<Flag> out, Flag f) { if (!disabled.contains(f.id())) out.add(f); }

    private static boolean updated(ObjectNode fields, String key) {
        return fields != null && fields.has(key) && "UPDATED".equals(Json.text(fields.get(key), "status"));
    }

    private static Double cf(JsonNode n) {
        JsonNode c = n == null ? null : n.get("cf");
        return c != null && c.isNumber() ? c.asDouble() : null;
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    private static String label(String id) {
        for (var d : com.ashford.terminal.schema.Schema.ASSETS) if (d.id().equals(id)) return d.label();
        for (var d : com.ashford.terminal.schema.Schema.MACRO) if (d.id().equals(id)) return d.label();
        return id;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iter(JsonNode obj) {
        if (obj == null || !obj.isObject()) return List.of();
        List<Map.Entry<String, JsonNode>> l = new ArrayList<>();
        obj.fields().forEachRemaining(l::add);
        return l;
    }
}
