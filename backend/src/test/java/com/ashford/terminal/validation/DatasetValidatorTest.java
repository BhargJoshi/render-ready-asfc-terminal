package com.ashford.terminal.validation;

import com.ashford.terminal.validation.Validated.Outcome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.ashford.terminal.service.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DatasetValidatorTest {
    private final DatasetValidator v = new DatasetValidator();
    private Validated run(ObjectNode fresh) { return v.validate(fresh, true, true, SourceTracer.of(SOURCE)); }
    private static boolean has(Validated r, String code) { return r.issues.stream().anyMatch(i -> i.code().equals(code)); }

    @Test void validExtractionIsAcceptedWithoutErrors() {
        Validated r = run(goodFresh());
        assertTrue(r.macro.get("fed").isValid());
        assertEquals("Bullish", r.bias.get("gold").value().bias().get("b").asText());
        assertEquals(80, r.bias.get("gold").value().bias().get("cf").asInt());
        assertFalse(r.issues.stream().anyMatch(i -> i.severity().equals("error")), r.issues.toString());
        assertFalse(has(r, "SOURCE_NOT_TRACEABLE"));
        assertFalse(has(r, "VALUE_NOT_FOUND_IN_SOURCE"));
    }

    @Test void biasLabelIsCaseNormalisedButUnknownLabelRejected() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("bias").get("gold")).put("b", "bullish");
        ((ObjectNode) f.get("bias").get("usBonds")).put("b", "Strongly Bullish");
        Validated r = run(f);
        assertEquals("Bullish", r.bias.get("gold").value().bias().get("b").asText());
        assertEquals(Outcome.INVALID, r.bias.get("usBonds").outcome());
        assertTrue(has(r, "INVALID_BIAS_LABEL"));
    }

    @Test void confidenceOutOfRangeBecomesUnknownNotClampedOrRescaled() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("bias").get("gold")).put("cf", 150);
        ((ObjectNode) f.get("bias").get("usBonds")).put("cf", 0.85);   // ambiguous scale: valid number in range, kept as-is
        Validated r = run(f);
        assertTrue(r.bias.get("gold").value().bias().get("cf").isNull());
        assertEquals("Bullish", r.bias.get("gold").value().bias().get("b").asText(), "label survives an unusable confidence");
        assertEquals(0.85, r.bias.get("usBonds").value().bias().get("cf").asDouble());
        assertTrue(has(r, "RANGE_VIOLATION"));
    }

    @Test void numericStringConfidenceIsCoercedAndNonNumericIsUnknown() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("bias").get("gold")).put("cf", "72");
        ((ObjectNode) f.get("bias").get("usBonds")).put("cf", "high");
        Validated r = run(f);
        assertEquals(72, r.bias.get("gold").value().bias().get("cf").asInt());
        assertTrue(r.bias.get("usBonds").value().bias().get("cf").isNull());
    }

    @Test void placeholderReadingNeverCountsAsData() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("cur", "N/A");
        Validated r = run(f);
        assertEquals(Outcome.ABSENT, r.macro.get("fed").outcome());
        assertTrue(has(r, "PLACEHOLDER_VALUE"));
    }

    @Test void readingWithoutANumberIsRejected() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("cur", "unchanged");
        Validated r = run(f);
        assertEquals(Outcome.INVALID, r.macro.get("fed").outcome());
        assertTrue(has(r, "UNPARSEABLE_READING"));
    }

    @Test void invalidTrendBecomesNaAndWrongTypeIsRejected() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("d", "sideways");
        ((ObjectNode) f.get("macro").get("cpiH")).set("cur", json("[1,2]"));
        Validated r = run(f);
        assertEquals("na", r.macro.get("fed").value().get("d").asText());
        assertEquals(Outcome.INVALID, r.macro.get("cpiH").outcome());
    }

    @Test void implausibleReadingIsKeptButFlagged() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("cur", "55.0%");
        Validated r = run(f);
        assertTrue(r.macro.get("fed").isValid());
        assertTrue(has(r, "IMPLAUSIBLE_RANGE"));
    }

    @Test void numbersAndExcerptsNotInSourceAreFlagged() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("cpiH")).put("cur", "9.9%");
        ((ObjectNode) f.get("bias").get("gold")).put("src", "Bullion surged to an all-time record on heavy central bank purchases across Asia.");
        Validated r = run(f);
        assertTrue(has(r, "VALUE_NOT_FOUND_IN_SOURCE"));
        assertTrue(has(r, "SOURCE_NOT_TRACEABLE"));
        assertTrue(r.macro.get("cpiH").isValid(), "flagged, not silently dropped");
    }

    @Test void formattingDifferencesInNumbersStillTrace() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("cur", "5.5%");   // source says 5.50%
        assertFalse(has(run(f), "VALUE_NOT_FOUND_IN_SOURCE"));
    }

    @Test void missingSourceReferenceIsWarned() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("macro").get("fed")).put("src", "");
        assertTrue(has(run(f), "MISSING_SOURCE_REFERENCE"));
    }

    @Test void listsDropItemsWithoutRequiredFieldDedupeAndCap() {
        ObjectNode f = goodFresh();
        var ev = (com.fasterxml.jackson.databind.node.ArrayNode) f.get("events");
        ev.add(json("{\"h\":\"\",\"cat\":\"Fed\"}"));
        ev.add(json("{\"h\":\"fed holds rates at 5.50%\",\"imp\":\"high\"}"));   // duplicate (case-insensitive)
        for (int i = 0; i < 10; i++) ev.add(json("{\"h\":\"Event " + i + "\",\"imp\":\"urgent\"}"));
        Validated r = run(f);
        assertEquals(6, r.events.value().size());
        assertTrue(has(r, "ITEM_DROPPED")); assertTrue(has(r, "DUPLICATE_ITEM")); assertTrue(has(r, "TRUNCATED_LIST"));
        assertEquals("", r.events.value().get(1).get("imp").asText(), "unknown importance stays unknown, not 'Low'");
    }

    @Test void narrativeStrengthUnknownIsNullNotFifty() {
        ObjectNode f = goodFresh();
        ((ObjectNode) f.get("narratives").get(0)).put("s", "n/a");
        Validated r = run(f);
        assertTrue(r.narratives.value().get(0).get("s").isNull());
    }

    @Test void nonObjectResponseYieldsNothingAndAnError() {
        Validated r = v.validate(json("[1,2,3]"), true, true, null);
        assertEquals(0, r.foundCount());
        assertTrue(has(r, "RAW_NOT_OBJECT"));
    }

    @Test void missingSectionsAreReportedForPartialExtractions() {
        ObjectNode f = goodFresh();
        f.remove("events"); f.remove("pos");
        Validated r = run(f);
        assertTrue(r.issues.stream().anyMatch(i -> i.code().equals("REQUIRED_SECTION_MISSING") && i.path().equals("events")));
        assertTrue(r.issues.stream().anyMatch(i -> i.code().equals("REQUIRED_SECTION_MISSING") && i.path().equals("pos")));
    }

    @Test void failedHalfIsNotValidatedAtAll() {
        Validated r = v.validate(goodFresh(), false, true, null);
        assertTrue(r.macro.isEmpty() && r.bias.isEmpty(), "nothing from the failed half is even looked at");
        assertEquals(Outcome.ABSENT, r.meta.getOrDefault("source", Validated.Entry.absent(java.util.List.of())).outcome());
        assertTrue(r.events.isValid());
    }
}
