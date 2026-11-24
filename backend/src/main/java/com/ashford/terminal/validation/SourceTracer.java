package com.ashford.terminal.validation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic "is this traceable to the text we actually analysed?" checks. They only ever produce
 * warnings: the model may legitimately normalise a number or lightly rephrase an excerpt.
 */
public final class SourceTracer {
    private static final Pattern TOKEN = Pattern.compile("\\p{L}[\\p{L}\\p{N}']*|\\p{N}+(?:[.,]\\p{N}+)*");
    private static final double MIN_OVERLAP = 0.8;

    private final String squashed;
    private final Set<String> tokens;
    private final Set<String> numbers;

    private SourceTracer(String text) {
        String norm = normalise(text);
        this.squashed = squash(norm);
        this.tokens = tokens(norm);
        this.numbers = Readings.allNumbers(text);
    }

    /** Returns null when there is no source text to check against (checks are then skipped). */
    public static SourceTracer of(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) return null;
        return new SourceTracer(sourceText);
    }

    /** True when the excerpt (or each ellipsis-separated fragment of it) appears in, or closely overlaps, the source. */
    public boolean excerptTraceable(String excerpt) {
        for (String frag : excerpt.split("\u2026|\\.\\.\\.")) {
            String norm = normalise(frag);
            Set<String> ft = tokens(norm);
            if (ft.size() < 4) continue;                       // too short to judge
            if (squashed.contains(squash(norm))) continue;     // verbatim (modulo punctuation/case)
            int hit = 0;
            for (String t : ft) if (tokens.contains(t)) hit++;
            if ((double) hit / ft.size() < MIN_OVERLAP) return false;
        }
        return true;
    }

    /** True when the first number of a reading appears somewhere in the source (formatting-insensitive). */
    public boolean readingNumberPresent(String reading) {
        String c = Readings.firstCanonical(reading);
        return c == null || numbers.contains(c);
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
                .replace('\u00a0', ' ');
    }

    private static String squash(String s) { return s.replaceAll("[^\\p{L}\\p{N}]+", ""); }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }
}
