package com.ashford.terminal.schema;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fixed dashboard schema. Mirrors MACRO_DEFS / ASSET_DEFS / POS_DEFS and the enum values used by the
 * Groq response schema in the front end. If the front-end schema changes, change it here too
 * (docs/API.md lists every place the two must agree).
 */
public final class Schema {
    private Schema() {}

    public record Def(String id, String label) {}

    public static final List<Def> MACRO = List.of(
            new Def("fed", "Fed Funds Rate"), new Def("cpiH", "Headline CPI"), new Def("cpiC", "Core CPI"),
            new Def("ppiH", "Headline PPI"), new Def("ppiC", "Core PPI"), new Def("real", "Real Rate"),
            new Def("y2", "2Y Yield"), new Def("y10", "10Y Yield"), new Def("dxy", "DXY"),
            new Def("wti", "WTI"), new Def("brent", "Brent"), new Def("vix", "VIX"));

    /** Order matters: the composite score iterates assets in this order (floating-point parity with the JS). */
    public static final List<Def> ASSETS = List.of(
            new Def("gold", "Gold"), new Def("nifty", "Nifty"), new Def("usEq", "US Equities"),
            new Def("usBonds", "US Bonds"), new Def("btc", "Bitcoin"));

    public static final List<Def> POS = List.of(
            new Def("risk", "Risk On / Risk Off"), new Def("inflation", "Inflation Theme"),
            new Def("growth", "Growth Theme"), new Def("liquidity", "Liquidity Theme"),
            new Def("fed", "Fed Theme"), new Def("dollar", "Dollar Theme"),
            new Def("bond", "Bond Theme"), new Def("gold", "Gold Theme"));

    /** Macro indicators that legitimately move slowly (monthly prints / meeting-driven). */
    public static final Set<String> SLOW_MACRO = Set.of("fed", "cpiH", "cpiC", "ppiH", "ppiC");

    public static final List<String> BIAS_LABELS = List.of("Bullish", "Bearish", "Neutral");
    public static final List<String> TRENDS = List.of("up", "down", "flat", "na");
    public static final List<String> POS_TONES = List.of("bullish", "bearish", "neutral", "macro", "warning", "none");
    public static final List<String> NARRATIVE_TONES = List.of("bullish", "bearish", "neutral", "macro", "warning");
    public static final List<String> IMPORTANCE = List.of("High", "Medium", "Low");
    public static final List<String> KNOWN_ASSET_TOKENS = List.of(
            "Gold", "Nifty", "US Equities", "US Bonds", "Bitcoin", "DXY", "WTI", "Brent");

    public static final List<String> MACRO_HALF_KEYS = List.of("meta", "macro", "bias", "matrix", "pos");
    public static final List<String> EVENTS_HALF_KEYS = List.of("events", "narratives", "watchlist", "today", "summary");

    // Same caps normalizeDataset() applies in the browser.
    public static final int EVENTS_MAX = 6, NARRATIVES_MAX = 6, WATCHLIST_MAX = 8, TODAY_MAX = 5, BULLETS_MAX = 8;

    /** Case-insensitive match against an allowed list; returns the canonical spelling or null. */
    public static String canonical(List<String> allowed, String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.toLowerCase(Locale.ROOT).equals(t)) return a;
        return null;
    }
}
