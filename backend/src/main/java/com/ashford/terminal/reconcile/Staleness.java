package com.ashford.terminal.reconcile;

import com.ashford.terminal.schema.Schema;

/** How old (by source time, else by update time) a value may be before it is labelled STALE. */
public record Staleness(long fastMinutes, long slowMinutes) {
    public long minutesFor(String key) {
        return key.startsWith("macro.") && Schema.SLOW_MACRO.contains(key.substring("macro.".length())) ? slowMinutes : fastMinutes;
    }
}
