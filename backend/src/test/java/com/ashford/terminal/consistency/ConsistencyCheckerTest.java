package com.ashford.terminal.consistency;

import com.ashford.terminal.scoring.RiskScoreCalculator;
import com.ashford.terminal.util.Json;
import com.ashford.terminal.validation.DatasetShape;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyCheckerTest {
    private static ObjectNode ds() { return DatasetShape.empty(); }
    private static void bias(ObjectNode d, String id, String b, Integer cf) {
        ObjectNode o = (ObjectNode) d.get("bias").get(id); o.put("b", b); if (cf != null) o.put("cf", cf);
    }
    private static void macro(ObjectNode d, String id, String cur, String prev, String dir, String note) {
        ObjectNode o = (ObjectNode) d.get("macro").get(id); o.put("cur", cur); o.put("prev", prev); o.put("d", dir); o.put("note", note);
    }
    private static java.util.List<ConsistencyChecker.Flag> run(ObjectNode d, Set<String> off) {
        return new ConsistencyChecker(off).check(d, RiskScoreCalculator.compute(d.get("bias")), null, null);
    }
    private static boolean has(java.util.List<ConsistencyChecker.Flag> f, String id) { return f.stream().anyMatch(x -> x.id().equals(id)); }

    @Test void cleanDatasetRaisesNothing() {
        ObjectNode d = ds();
        bias(d, "gold", "Bullish", 70);
        ((ObjectNode) d.get("matrix").get("gold")).put("b", "Bullish").put("bd", "x");
        assertTrue(run(d, Set.of()).isEmpty());
    }

    @Test void biasVsMatrixLabelMismatchIsFlaggedAndNothingIsChanged() {
        ObjectNode d = ds();
        bias(d, "gold", "Bullish", 70);
        ((ObjectNode) d.get("matrix").get("gold")).put("b", "Bearish");
        String before = d.toString();
        var f = run(d, Set.of());
        assertTrue(has(f, "BIAS_MATRIX_LABEL_MISMATCH"));
        assertEquals("warn", f.get(0).severity());
        assertFalse(f.get(0).heuristic());
        assertEquals(before, d.toString(), "checker must never modify AI-generated content");
    }

    @Test void conventionalRelationshipsAreInfoSeverityWithExplicitAssumptions() {
        ObjectNode d = ds();
        bias(d, "usBonds", "Bullish", 70);
        macro(d, "y10", "4.5%", "4.2%", "up", "");
        var f = run(d, Set.of());
        var flag = f.stream().filter(x -> x.id().equals("BONDS_VS_YIELDS")).findFirst().orElseThrow();
        assertEquals("info", flag.severity());
        assertTrue(flag.heuristic());
        assertTrue(flag.assumption().contains("different horizon"));
    }

    @Test void goldAgainstRealRatesAndDollarNeedsAllConditions() {
        ObjectNode d = ds();
        bias(d, "gold", "Bullish", 80);
        macro(d, "real", "2.1%", "1.9%", "up", "");
        assertFalse(has(run(d, Set.of()), "GOLD_VS_REAL_RATES_DOLLAR"));
        macro(d, "dxy", "105", "104", "up", "");
        assertTrue(has(run(d, Set.of()), "GOLD_VS_REAL_RATES_DOLLAR"));
        bias(d, "gold", "Bullish", 40);
        assertFalse(has(run(d, Set.of()), "GOLD_VS_REAL_RATES_DOLLAR"), "low confidence is not flagged");
    }

    @Test void macroTrendContradictingItsOwnNumbersIsFlagged() {
        ObjectNode d = ds();
        macro(d, "cpiH", "3.2%", "3.4%", "up", "");
        macro(d, "fed", "5.50%", "5.50%", "flat", "");
        macro(d, "dxy", "104.2", "104.0", "flat", "");
        var f = run(d, Set.of());
        assertEquals(1, f.stream().filter(x -> x.id().equals("MACRO_TREND_VS_VALUES")).count());
        assertEquals("macro.cpiH", f.stream().filter(x -> x.id().equals("MACRO_TREND_VS_VALUES")).findFirst().get().refs().get(0));
    }

    @Test void noteWordingContradictingTrendIsFlaggedOnlyWhenUnambiguous() {
        ObjectNode d = ds();
        macro(d, "cpiH", "3.2%", "3.4%", "down", "Inflation accelerated sharply");
        macro(d, "cpiC", "3.0%", "3.1%", "down", "Eased even as headline rose");
        var f = run(d, Set.of());
        assertTrue(f.stream().anyMatch(x -> x.id().equals("MACRO_NOTE_VS_TREND") && x.refs().contains("macro.cpiH")));
        assertFalse(f.stream().anyMatch(x -> x.id().equals("MACRO_NOTE_VS_TREND") && x.refs().contains("macro.cpiC")), "mixed wording is not flagged");
    }

    @Test void positioningAgainstAssetBias() {
        ObjectNode d = ds();
        bias(d, "gold", "Bearish", 60);
        ((ObjectNode) d.get("pos").get(7)).put("v", "Bullish").put("tone", "bullish");
        assertTrue(has(run(d, Set.of()), "POS_VS_ASSET_BIAS"));
    }

    @Test void rulesCanBeDisabledById() {
        ObjectNode d = ds();
        bias(d, "gold", "Bullish", 70);
        ((ObjectNode) d.get("matrix").get("gold")).put("b", "Bearish");
        assertTrue(run(d, Set.of("BIAS_MATRIX_LABEL_MISMATCH")).isEmpty());
    }

    @Test void scoreDisclosuresCoverDefaultedConfidence() {
        ObjectNode d = ds();
        bias(d, "gold", "Bullish", null);
        assertTrue(has(run(d, Set.of()), "SCORE_CONFIDENCE_DEFAULTED"));
    }

    @Test void reversalAgainstPreviousOnlyForUpdatedValues() {
        ObjectNode now = ds(), prev = ds();
        bias(now, "gold", "Bearish", 80); bias(prev, "gold", "Bullish", 75);
        ObjectNode fields = Json.obj();
        fields.putObject("bias.gold").put("status", "UPDATED");
        var f = new ConsistencyChecker(Set.of()).check(now, RiskScoreCalculator.compute(now.get("bias")), fields, prev);
        assertTrue(has(f, "BIAS_REVERSAL_VS_PREVIOUS"));
        fields.putObject("bias.gold").put("status", "CARRIED_FORWARD");
        f = new ConsistencyChecker(Set.of()).check(now, RiskScoreCalculator.compute(now.get("bias")), fields, prev);
        assertFalse(has(f, "BIAS_REVERSAL_VS_PREVIOUS"));
        assertTrue(has(f, "SCORE_USES_NON_FRESH_INPUTS"));
    }
}
