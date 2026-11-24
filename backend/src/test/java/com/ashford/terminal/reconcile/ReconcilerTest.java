package com.ashford.terminal.reconcile;

import com.ashford.terminal.service.ApiException;
import com.ashford.terminal.service.ProcessingService;
import com.ashford.terminal.service.TestSupport.MutableClock;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.ashford.terminal.service.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconcilerTest {
    MutableClock clock;
    ProcessingService svc;

    @BeforeEach void setUp() { clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z")); svc = service(clock); }

    private static String status(JsonNode resp, String key) { return resp.get("integrity").get("fields").get(key).get("status").asText(); }
    private static String reason(JsonNode resp, String key) { return resp.get("integrity").get("fields").get(key).get("reason").asText(); }

    private ObjectNode first() {
        ObjectNode req = request(goodFresh(), "groq", "ok", "ok");
        req.put("sourceText", SOURCE);
        return svc.process(req);
    }

    /** Next request carrying the previous response's dataset + integrity, like the browser does. */
    private ObjectNode next(JsonNode prev, ObjectNode fresh, String macro, String events) {
        ObjectNode req = request(fresh, "groq", macro, events);
        req.set("previous", prev.get("dataset"));
        req.set("previousIntegrity", prev.get("integrity"));
        req.put("sourceText", SOURCE);
        return req;
    }

    @Test void firstRunMarksFoundFieldsUpdatedAndUnmentionedFieldsMissing() {
        ObjectNode r = first();
        assertEquals("UPDATED", status(r, "macro.fed"));
        assertEquals("UPDATED", status(r, "bias.gold"));
        assertEquals("MISSING", status(r, "macro.vix"));
        assertEquals("MISSING", status(r, "bias.btc"));
        assertFalse(r.get("integrity").get("fields").get("macro.vix").get("hasValue").asBoolean());
        assertEquals("", r.get("dataset").get("macro").get("vix").get("cur").asText(), "missing stays empty, never zero/neutral");
        assertTrue(r.get("dataset").get("bias").get("btc").get("cf").isNull());
        assertEquals(r.get("integrity").get("summary").get("UPDATED").asInt() + r.get("integrity").get("summary").get("MISSING").asInt(), 33);
    }

    @Test void unmentionedValueIsCarriedForwardWithAgeNotPresentedAsNew() {
        ObjectNode r1 = first();
        clock.advanceMinutes(90);
        ObjectNode fresh = (ObjectNode) json("{\"macro\":{\"y10\":{\"cur\":\"4.10%\",\"prev\":\"\",\"cons\":\"\",\"d\":\"down\",\"note\":\"\",\"src\":\"The 10-year Treasury yield fell to 4.10%.\"}},\"bias\":{},\"matrix\":{},\"pos\":{},\"meta\":{},\"events\":[],\"narratives\":[],\"watchlist\":[],\"today\":[],\"summary\":{\"bullets\":[]}}");
        ObjectNode r2 = svc.process(next(r1, fresh, "ok", "ok"));
        assertEquals("UPDATED", status(r2, "macro.y10"));
        assertEquals("CARRIED_FORWARD", status(r2, "macro.fed"));
        assertEquals("not_mentioned", reason(r2, "macro.fed"));
        assertEquals(90, r2.get("integrity").get("fields").get("macro.fed").get("ageMinutes").asInt());
        assertEquals("5.50%", r2.get("dataset").get("macro").get("fed").get("cur").asText(), "old value preserved");
        assertEquals("CARRIED_FORWARD", status(r2, "events"));
        assertEquals(r1.get("dataset").get("events"), r2.get("dataset").get("events"));
    }

    @Test void carriedValuesBecomeStaleAfterThresholdWithCauseKept() {
        ObjectNode r1 = first();
        clock.advanceMinutes(721);
        ObjectNode empty = (ObjectNode) json("{\"macro\":{},\"bias\":{},\"matrix\":{},\"pos\":{},\"meta\":{},\"events\":[],\"narratives\":[],\"watchlist\":[],\"today\":[],\"summary\":{\"bullets\":[]}}");
        ObjectNode r2 = svc.process(next(r1, empty, "ok", "ok"));
        assertEquals("STALE", status(r2, "bias.gold"));
        assertEquals("not_mentioned", reason(r2, "bias.gold"));
        assertEquals("CARRIED_FORWARD", status(r2, "macro.fed"), "slow macro uses the longer threshold");
        clock.advanceMinutes(10080);
        ObjectNode r3 = svc.process(next(r2, empty, "ok", "ok"));
        assertEquals("STALE", status(r3, "macro.fed"));
    }

    @Test void failedSectionKeepsOldValuesLabelledExtractionFailed() {
        ObjectNode r1 = first();
        clock.advanceMinutes(10);
        ObjectNode req = next(r1, goodFresh(), "failed", "ok");
        req.with("extraction").with("errors").put("macro", "Groq returned 429");
        ObjectNode r2 = svc.process(req);
        assertEquals("EXTRACTION_FAILED", status(r2, "macro.fed"));
        assertEquals("EXTRACTION_FAILED", status(r2, "bias.gold"));
        assertEquals(r1.get("dataset").get("bias"), r2.get("dataset").get("bias"), "failed half never overwrites");
        assertEquals("UPDATED", status(r2, "events"), "other half still applies");
        assertTrue(r2.get("issues").toString().contains("SECTION_FAILED"));
    }

    @Test void failedSectionWithNoPreviousIsExtractionFailedNotMissing() {
        ObjectNode r = svc.process(request(goodFresh(), "groq", "failed", "ok"));
        assertEquals("EXTRACTION_FAILED", status(r, "macro.fed"));
        assertFalse(r.get("integrity").get("fields").get("macro.fed").get("hasValue").asBoolean());
        // 1 event + 1 narrative + 1 watchlist + 2 bullets = 5; nothing from the failed macro half is counted
        assertEquals(5, r.get("foundCount").asInt());
        assertEquals("", r.get("dataset").get("bias").get("gold").get("b").asText());
    }

    @Test void bothHalvesFailedKeepsEverythingAndAppliesNothing() {
        ObjectNode r1 = first();
        ObjectNode r2 = svc.process(next(r1, (ObjectNode) json("{}"), "failed", "failed"));
        assertEquals(0, r2.get("appliedCount").asInt());
        assertEquals(r1.get("dataset"), r2.get("dataset"));
    }

    @Test void invalidValueIsRejectedAndPreviousKept() {
        ObjectNode r1 = first();
        ObjectNode bad = goodFresh();
        ((ObjectNode) bad.get("bias").get("gold")).put("b", "Mixed");
        ObjectNode r2 = svc.process(next(r1, bad, "ok", "ok"));
        assertEquals("REJECTED_INVALID", status(r2, "bias.gold"));
        assertEquals("Bullish", r2.get("dataset").get("bias").get("gold").get("b").asText());
        assertEquals(80, r2.get("dataset").get("bias").get("gold").get("cf").asInt());
    }

    @Test void placeholderCannotOverwriteGoodValue() {
        ObjectNode r1 = first();
        ObjectNode bad = goodFresh();
        ((ObjectNode) bad.get("macro").get("fed")).put("cur", "N/A");
        ObjectNode r2 = svc.process(next(r1, bad, "ok", "ok"));
        assertEquals("5.50%", r2.get("dataset").get("macro").get("fed").get("cur").asText());
        assertEquals("CARRIED_FORWARD", status(r2, "macro.fed"));
    }

    @Test void demoPreviousIsNeverUsedAsFallback() {
        ObjectNode sample = goodFresh();
        ObjectNode first = first();
        ObjectNode req = request((ObjectNode) json("{\"macro\":{},\"bias\":{},\"matrix\":{},\"pos\":{},\"meta\":{},\"events\":[],\"narratives\":[],\"watchlist\":[],\"today\":[],\"summary\":{\"bullets\":[]}}"), "groq", "ok", "ok");
        req.set("previous", first.get("dataset"));
        req.put("previousIsDemo", true);
        ObjectNode r = svc.process(req);
        assertEquals("MISSING", status(r, "macro.fed"));
        assertEquals("", r.get("dataset").get("macro").get("fed").get("cur").asText());
        assertTrue(r.get("issues").toString().contains("PREVIOUS_DEMO_IGNORED"));
        assertNotNull(sample);
    }

    @Test void olderSourceDoesNotOverwriteNewerValue() {
        ObjectNode req1 = request(goodFresh(), "groq", "ok", "ok");
        req1.put("sourceText", SOURCE); req1.put("sourceAsOf", "2026-10-17T09:00:00Z");
        ObjectNode r1 = svc.process(req1);
        assertEquals("2026-10-17T09:00:00Z", r1.get("integrity").get("fields").get("macro.fed").get("asOf").asText());

        ObjectNode older = goodFresh();
        ((ObjectNode) older.get("bias").get("gold")).put("b", "Bearish");
        ObjectNode req2 = next(r1, older, "ok", "ok");
        req2.put("sourceAsOf", "2026-10-15T09:00:00Z");
        ObjectNode r2 = svc.process(req2);
        assertEquals("Bullish", r2.get("dataset").get("bias").get("gold").get("b").asText());
        assertEquals("CARRIED_FORWARD", status(r2, "bias.gold"));
        assertEquals("older_source", reason(r2, "bias.gold"));
        assertEquals(0, r2.get("appliedCount").asInt());
        assertTrue(r2.get("foundCount").asInt() > 0, "found but deliberately not applied");
    }

    @Test void updateWithAnOldSourceIsLabelledStaleNotFresh() {
        ObjectNode req = request(goodFresh(), "groq", "ok", "ok");
        req.put("sourceAsOf", "2026-10-10T09:00:00Z");
        ObjectNode r = svc.process(req);
        assertEquals("STALE", status(r, "bias.gold"));
    }

    @Test void thinSummaryDoesNotWipeFullerRecap() {
        ObjectNode r1 = first();
        ObjectNode thin = goodFresh();
        ((com.fasterxml.jackson.databind.node.ArrayNode) thin.get("summary").get("bullets")).remove(1);
        ObjectNode r2 = svc.process(next(r1, thin, "ok", "ok"));
        assertEquals(2, r2.get("dataset").get("summary").get("bullets").size());
        assertEquals("CARRIED_FORWARD", status(r2, "summary"));
        assertEquals("thin_update", reason(r2, "summary"));
    }

    @Test void foundCountMatchesLegacyCountingRule() {
        ObjectNode r = first();
        // meta 3 + macro 3 + bias 2 + pos 1 + events 1 + narratives 1 + watchlist 1 + today 0 + bullets 2
        assertEquals(14, r.get("foundCount").asInt());
    }

    @Test void legacyPersistedDatasetIsNeverReportedAsFreshlyVerified() {
        ObjectNode r1 = first();
        ObjectNode req = new ObjectNode(Json.MAPPER.getNodeFactory());
        req.set("dataset", r1.get("dataset"));
        req.put("savedAt", "2026-10-17T06:00:00Z");           // 4h before "now"; no integrity supplied
        ObjectNode r = svc.reevaluate(req);
        JsonNode f = r.get("integrity").get("fields");
        assertEquals("CARRIED_FORWARD", f.get("macro.fed").get("status").asText());
        assertTrue(f.get("macro.fed").get("legacy").asBoolean());
        assertEquals(240, f.get("macro.fed").get("ageMinutes").asInt());
        assertEquals("MISSING", f.get("macro.vix").get("status").asText());
    }

    @Test void reevaluateAgesUpdatedFieldsToStaleWithoutChangingValues() {
        ObjectNode r1 = first();
        clock.advanceMinutes(800);
        ObjectNode req = Json.obj();
        req.set("dataset", r1.get("dataset")); req.set("integrity", r1.get("integrity"));
        ObjectNode r = svc.reevaluate(req);
        assertEquals("STALE", r.get("integrity").get("fields").get("bias.gold").get("status").asText());
        assertEquals("aged", r.get("integrity").get("fields").get("bias.gold").get("reason").asText());
        assertEquals("UPDATED", status(r1, "bias.gold"), "the earlier response is untouched");
        // idempotent: re-evaluating the already-aged integrity changes nothing
        ObjectNode req2 = Json.obj();
        req2.set("dataset", r1.get("dataset")); req2.set("integrity", r.get("integrity"));
        assertEquals(r.get("integrity").get("fields"), svc.reevaluate(req2).get("integrity").get("fields"));
    }

    @Test void demoDatasetIsNotEvaluated() {
        ObjectNode req = Json.obj();
        req.set("dataset", first().get("dataset")); req.put("isDemo", true);
        assertTrue(svc.reevaluate(req).get("demo").asBoolean());
    }

    @Test void badRequestsAreRejectedCleanly() {
        assertThrows(ApiException.class, () -> svc.process(json("[]")));
        ObjectNode req = request(goodFresh(), "groq", "ok", "ok");
        req.put("previous", "not a dataset");
        ApiException e = assertThrows(ApiException.class, () -> svc.process(req));
        assertEquals(400, e.status);
        assertThrows(ApiException.class, () -> svc.score(json("{}")));
    }

    @Test void replayingTheSameRequestGivesTheSameResultAndNeverMutatesInput() {
        ObjectNode req = request(goodFresh(), "groq", "ok", "ok");
        req.put("sourceText", SOURCE);
        String before = req.toString();
        ObjectNode a = svc.process(req), b = svc.process(req);
        assertEquals(before, req.toString(), "input untouched");
        assertEquals(a, b, "deterministic for a fixed clock: replays cannot create duplicates or drift");
    }
}
