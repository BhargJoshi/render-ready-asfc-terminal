package com.ashford.terminal.service;

import com.ashford.terminal.reconcile.Staleness;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;

public final class TestSupport {
    private TestSupport() {}

    public static final class MutableClock extends Clock {
        public Instant now;
        public MutableClock(Instant start) { this.now = start; }
        public void advanceMinutes(long m) { now = now.plus(m, ChronoUnit.MINUTES); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    public static ProcessingService service(Clock c) { return new ProcessingService(c, new Staleness(720, 10080), Set.of()); }

    public static JsonNode json(String s) {
        try { return Json.MAPPER.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** A request in the shape the browser sends. */
    public static ObjectNode request(JsonNode fresh, String engine, String macro, String events) {
        ObjectNode r = Json.obj();
        r.put("runId", "run-1");
        r.set("fresh", fresh);
        ObjectNode ex = r.putObject("extraction");
        ex.put("engine", engine);
        ObjectNode s = ex.putObject("sections"); s.put("macro", macro); s.put("events", events);
        return r;
    }

    public static final String SOURCE = "The Fed held rates at 5.50% on Wednesday. Headline CPI cooled to 3.2% from 3.4% in September. "
            + "Gold rallied as the dollar softened and yields fell. The 10-year Treasury yield fell to 4.10%. "
            + "Analysts expect the next catalyst to be the payrolls report on Friday.";

    /** A well-formed fresh extraction consistent with SOURCE. */
    public static ObjectNode goodFresh() {
        return (ObjectNode) json("""
        {"meta":{"source":"Reuters","session":"US Close","headline":"Gold rallies as yields fall"},
         "macro":{
           "fed":{"cur":"5.50%","prev":"5.50%","cons":"5.50%","d":"flat","note":"Held at cycle high","src":"The Fed held rates at 5.50% on Wednesday."},
           "cpiH":{"cur":"3.2%","prev":"3.4%","cons":"","d":"down","note":"Cooled again","src":"Headline CPI cooled to 3.2% from 3.4% in September."},
           "y10":{"cur":"4.10%","prev":"","cons":"","d":"down","note":"Yields fell","src":"The 10-year Treasury yield fell to 4.10%."}},
         "bias":{
           "gold":{"b":"Bullish","cf":80,"pd":"Falling yields","br":"Hawkish Fed","nc":"Payrolls Friday","src":"Gold rallied as the dollar softened and yields fell."},
           "usBonds":{"b":"Bullish","cf":70,"pd":"Yields falling","br":"Sticky inflation","nc":"","src":"The 10-year Treasury yield fell to 4.10%."}},
         "matrix":{
           "gold":{"bd":"Softer dollar, lower yields","brd":"Hawkish Fed","cd":"Lower yields","b":"Bullish","cf":80,"src":"Gold rallied as the dollar softened and yields fell."},
           "usBonds":{"bd":"Falling yields","brd":"Sticky inflation","cd":"Yields","b":"Bullish","cf":70,"src":"The 10-year Treasury yield fell to 4.10%."}},
         "pos":{"gold":{"v":"Bullish","tone":"bullish","src":"Gold rallied as the dollar softened and yields fell."}},
         "events":[{"cat":"Fed","t":"","dt":"Wednesday","h":"Fed holds rates at 5.50%","w":"Signals patience","aa":"US Bonds, Gold","imp":"High","src":"The Fed held rates at 5.50% on Wednesday."}],
         "narratives":[{"n":"Disinflation","tone":"bullish","s":70,"aa":"Gold","src":"Headline CPI cooled to 3.2% from 3.4% in September."}],
         "watchlist":[{"t":"","dt":"Friday","e":"Payrolls report","f":"","imp":"High","aa":"US Equities","src":"Analysts expect the next catalyst to be the payrolls report on Friday."}],
         "today":[],
         "summary":{"bullets":["Disinflation and falling yields are supporting gold and duration.","The Fed is on hold, leaving data as the swing factor."]}}
        """);
    }
}
