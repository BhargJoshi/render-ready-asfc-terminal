package com.ashford.terminal.scoring;

import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ScoreParityTest {
    /** 4000 randomised cases whose expected values were produced by the ORIGINAL JavaScript computeRiskScore(). */
    @Test void javaMatchesOriginalJavaScriptOnEveryFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/score-parity.json")) {
            JsonNode cases = Json.MAPPER.readTree(in);
            assertTrue(cases.size() >= 4000);
            int nulls = 0;
            for (JsonNode c : cases) {
                var result = RiskScoreCalculator.compute(c.get("bias"));
                if (c.get("expected").isNull()) { assertNull(result.value(), c.toString()); nulls++; }
                else assertEquals(c.get("expected").asInt(), result.value(), c.toString());
            }
            assertTrue(nulls > 0, "fixtures must include the 'no score' case");
        }
    }

    @Test void knownValuesAndDisclosure() {
        var r = RiskScoreCalculator.compute(Json.MAPPER.createObjectNode()
                .set("gold", Json.MAPPER.createObjectNode().put("b", "Bullish").put("cf", 80)));
        assertEquals(100, r.value());
        var none = RiskScoreCalculator.compute(Json.MAPPER.createObjectNode());
        assertNull(none.value());
    }

    @Test void unknownConfidenceKeepsLegacyWeightOf50ButIsDisclosed() throws Exception {
        JsonNode b = Json.MAPPER.readTree("{\"gold\":{\"b\":\"Bullish\",\"cf\":null},\"btc\":{\"b\":\"Bearish\",\"cf\":50}}");
        var r = RiskScoreCalculator.compute(b);
        assertEquals(0, r.value());
        assertTrue(r.inputs().get(0).confidenceDefaulted());
        assertFalse(r.inputs().get(1).confidenceDefaulted());
        assertEquals("gold", r.toJson().get("defaultedConfidence").get(0).asText());
    }

    @Test void zeroTotalWeightMeansNoScoreNotZero() throws Exception {
        JsonNode b = Json.MAPPER.readTree("{\"gold\":{\"b\":\"Bullish\",\"cf\":0}}");
        assertNull(RiskScoreCalculator.compute(b).value());
    }
}
