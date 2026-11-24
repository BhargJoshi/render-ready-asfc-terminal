package com.ashford.terminal.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric helpers for macro readings ("5.50%", "$78.40", "4.25%-4.50%", "104.2"). */
public final class Readings {
    private Readings() {}

    private static final Pattern NUM = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");
    private static final Pattern BPS = Pattern.compile("(?i)\\bbps?\\b|basis points?");
    private static final Pattern MAGNITUDE = Pattern.compile("(?i)\\d\\s?(?:k|m|b|bn|t|trn|thousand|million|billion|trillion)\\b");

    public static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    public static boolean isBasisPoints(String s) { return BPS.matcher(s).find(); }
    public static boolean hasMagnitudeSuffix(String s) { return MAGNITUDE.matcher(s).find(); }

    /** First number in the text, honouring a leading minus sign. */
    public static OptionalDouble first(String s) {
        if (s == null) return OptionalDouble.empty();
        Matcher m = NUM.matcher(s);
        if (!m.find()) return OptionalDouble.empty();
        double v;
        try { v = Double.parseDouble(m.group().replace(",", "")); }
        catch (NumberFormatException e) { return OptionalDouble.empty(); }
        int st = m.start();
        if (st > 0) {
            int j = st - 1;
            while (j >= 0 && (s.charAt(j) == ' ' || s.charAt(j) == '$')) j--;
            if (j >= 0 && (s.charAt(j) == '-' || s.charAt(j) == '\u2212')
                    && (j == 0 || !Character.isLetterOrDigit(s.charAt(j - 1)))) v = -v;
        }
        return OptionalDouble.of(v);
    }

    /** Canonical unsigned form so "5.50" and "5.5" and "1,200" compare equal. */
    static String canon(String digits) {
        try { return new BigDecimal(digits.replace(",", "")).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return digits; }
    }

    static Set<String> allNumbers(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) out.add(canon(m.group()));
        return out;
    }

    /** Canonical form of the first number in a reading, or null. */
    static String firstCanonical(String s) {
        Matcher m = NUM.matcher(s);
        return m.find() ? canon(m.group()) : null;
    }
}
