package com.ashford.terminal.scoring;

import com.ashford.terminal.schema.Schema;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite risk / positioning score. This is a line-for-line port of computeRiskScore() in the
 * terminal's JavaScript so that Java and the browser fallback always agree:
 *
 *   polarity = +1 Bullish, -1 Bearish, 0 any other non-empty label
 *   w        = (confidence if present else 50) / 100
 *   score    = Math.round( (sum(polarity * w) / sum(w)) * 100 )      (null when sum(w) == 0)
 *
 * Math.round has the same floor(x + 0.5) semantics in Java and JavaScript, and both use IEEE-754 doubles,
 * so results are bit-identical (see ScoreParityTest, which replays the original JS against this class).
 *
 * The "missing confidence counts as 50" rule is preserved because the task requires an unchanged formula; it is
 * disclosed in the result (confidenceDefaulted) so callers can show that an input's weight was assumed.
 */
public final class RiskScoreCalculator {
    private RiskScoreCalculator() {}

    public static final double DEFAULT_CONFIDENCE = 50;

    public record Input(String asset, String label, String bias, int polarity, Double confidence,
                        double weight, boolean confidenceDefaulted) {}

    public record Result(Integer value, List<Input> inputs) {
        public ObjectNode toJson() {
            ObjectNode o = Json.obj();
            if (value == null) o.putNull("value"); else o.put("value", value);
            o.put("formula", "round(100 * sum(polarity*w) / sum(w)); w = (confidence, or 50 if unknown) / 100");
            ArrayNode in = o.putArray("inputs");
            ArrayNode defaulted = o.putArray("defaultedConfidence");
            for (Input i : inputs) {
                ObjectNode n = in.addObject();
                n.put("asset", i.asset()); n.put("label", i.label()); n.put("bias", i.bias());
                n.put("polarity", i.polarity());
                if (i.confidence() == null) n.putNull("confidence"); else n.set("confidence", Json.num(i.confidence()));
                n.put("weight", i.weight());
                n.put("confidenceDefaulted", i.confidenceDefaulted());
                if (i.confidenceDefaulted()) defaulted.add(i.asset());
            }
            return o;
        }
    }

    /** @param biasByAsset the dataset's "bias" object (asset id -> {b, cf, ...}) */
    public static Result compute(JsonNode biasByAsset) {
        double sum = 0, weight = 0;
        List<Input> inputs = new ArrayList<>();
        for (Schema.Def def : Schema.ASSETS) {
            JsonNode v = biasByAsset == null ? null : biasByAsset.get(def.id());
            String b = Json.text(v, "b");
            if (b.isEmpty()) continue;                                   // JS: if(!v || !v.b) return;
            int polarity = b.equals("Bullish") ? 1 : b.equals("Bearish") ? -1 : 0;
            JsonNode cfNode = v.get("cf");
            boolean has = cfNode != null && cfNode.isNumber();           // JS: v.cf != null (always a number or null)
            double cf = has ? cfNode.asDouble() : DEFAULT_CONFIDENCE;
            double w = cf / 100;
            sum += polarity * w;
            weight += w;
            inputs.add(new Input(def.id(), def.label(), b, polarity, has ? cf : null, w, !has));
        }
        if (weight == 0) return new Result(null, inputs);
        return new Result((int) Math.round((sum / weight) * 100), inputs);
    }
}
