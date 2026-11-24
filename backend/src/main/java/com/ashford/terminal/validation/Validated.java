package com.ashford.terminal.validation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of validating one raw extraction. Every field is in exactly one state:
 * ABSENT (nothing usable was provided), VALID (normalised value present), INVALID (something was provided but rejected).
 * The distinction matters downstream: only ABSENT may fall back to previously loaded data silently-as-"not mentioned";
 * INVALID is reported as a rejection.
 */
public final class Validated {
    public enum Outcome { ABSENT, VALID, INVALID }

    public record Entry<T>(Outcome outcome, T value, List<Issue> issues) {
        public static <T> Entry<T> absent(List<Issue> i) { return new Entry<>(Outcome.ABSENT, null, List.copyOf(i)); }
        public static <T> Entry<T> valid(T v, List<Issue> i) { return new Entry<>(Outcome.VALID, v, List.copyOf(i)); }
        public static <T> Entry<T> invalid(List<Issue> i) { return new Entry<>(Outcome.INVALID, null, List.copyOf(i)); }
        public boolean isValid() { return outcome == Outcome.VALID; }
    }

    public record BiasPair(ObjectNode bias, ObjectNode matrix) {}

    public final Map<String, Entry<ObjectNode>> macro = new LinkedHashMap<>();
    public final Map<String, Entry<BiasPair>> bias = new LinkedHashMap<>();
    public final Map<String, Entry<ObjectNode>> pos = new LinkedHashMap<>();
    public final Map<String, Entry<String>> meta = new LinkedHashMap<>();
    public Entry<ArrayNode> events = Entry.absent(List.of());
    public Entry<ArrayNode> narratives = Entry.absent(List.of());
    public Entry<ArrayNode> watchlist = Entry.absent(List.of());
    public Entry<ArrayNode> today = Entry.absent(List.of());
    public Entry<ArrayNode> bullets = Entry.absent(List.of());

    /** Every issue found, in discovery order (also available per entry). */
    public final List<Issue> issues = new ArrayList<>();

    /** Number of valid signals found in this extraction (same counting rule the browser's merge used). */
    public int foundCount() {
        int n = 0;
        for (var e : macro.values()) if (e.isValid()) n++;
        for (var e : bias.values()) if (e.isValid()) n++;
        for (var e : pos.values()) if (e.isValid()) n++;
        for (var e : meta.values()) if (e.isValid()) n++;
        if (events.isValid()) n += events.value().size();
        if (narratives.isValid()) n += narratives.value().size();
        if (watchlist.isValid()) n += watchlist.value().size();
        if (today.isValid()) n += today.value().size();
        if (bullets.isValid()) n += bullets.value().size();
        return n;
    }
}
