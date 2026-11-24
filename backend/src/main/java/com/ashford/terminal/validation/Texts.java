package com.ashford.terminal.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** String / confidence cleaning shared by the validator. Never invents a value: unusable input becomes "" or null. */
final class Texts {
    private Texts() {}

    /** Strings models emit when they mean "nothing here". Treating them as data would overwrite real values. */
    static final Set<String> PLACEHOLDERS = Set.of(
            "n/a", "na", "none", "null", "unknown", "not stated", "not available", "not mentioned",
            "not specified", "tbd", "nil", "undefined", "-", "\u2014", "\u2013", "?");

    record Str(String value, boolean badType) {}

    static boolean isPlaceholder(String s) {
        return PLACEHOLDERS.contains(s.trim().toLowerCase(Locale.ROOT));
    }

    static Str clean(JsonNode n, String path, int max, List<Issue> issues) {
        if (n == null || n.isNull() || n.isMissingNode()) return new Str("", false);
        String s;
        if (n.isTextual()) {
            s = n.asText();
        } else if (n.isNumber()) {
            s = n.asText();
            issues.add(Issue.info("COERCED_TYPE", path, "Number received where text was expected; kept as text."));
        } else {
            issues.add(Issue.warn("INVALID_TYPE", path, "Expected text but received " + n.getNodeType() + "; value ignored."));
            return new Str("", true);
        }
        s = s.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").trim();
        if (s.isEmpty()) return new Str("", false);
        if (isPlaceholder(s)) {
            issues.add(Issue.info("PLACEHOLDER_VALUE", path, "Placeholder text \"" + s + "\" treated as no value."));
            return new Str("", false);
        }
        if (s.length() > max) {
            issues.add(Issue.info("TRUNCATED", path, "Text longer than " + max + " characters was truncated."));
            s = s.substring(0, max).trim() + "\u2026";
        }
        return new Str(s, false);
    }

    /**
     * Confidence / strength: JSON number (or numeric string) in [0,100], else null. Out-of-range values are
     * NOT rescaled (0.85 could mean 0.85% or 85%); they become "unknown" and are reported.
     */
    static Double score0to100(JsonNode n, String path, List<Issue> issues) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        double v;
        if (n.isNumber()) {
            v = n.asDouble();
        } else if (n.isTextual()) {
            String t = n.asText().trim().replace("%", "");
            if (t.isEmpty() || isPlaceholder(t)) return null;
            try { v = Double.parseDouble(t); }
            catch (NumberFormatException e) {
                issues.add(Issue.warn("NUMBER_INVALID", path, "\"" + n.asText() + "\" is not a number; treated as unknown."));
                return null;
            }
            issues.add(Issue.info("COERCED_TYPE", path, "Numeric string converted to a number."));
        } else {
            issues.add(Issue.warn("INVALID_TYPE", path, "Expected a number but received " + n.getNodeType() + "; treated as unknown."));
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 100) {
            issues.add(Issue.warn("RANGE_VIOLATION", path, "Value " + v + " is outside 0-100; treated as unknown (not rescaled)."));
            return null;
        }
        return v;
    }
}
