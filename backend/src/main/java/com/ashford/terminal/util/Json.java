package com.ashford.terminal.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Small JSON helpers shared by every layer. */
public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }

    /** Integral doubles are emitted as integers so confidence 72 serialises as 72, not 72.0. */
    public static JsonNode num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return MAPPER.getNodeFactory().numberNode((long) v);
        return MAPPER.getNodeFactory().numberNode(v);
    }

    /** Text value of a field, or "" when absent / null / not a string. */
    public static String text(JsonNode n, String field) {
        if (n == null) return "";
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }

    public static JsonNode parse(byte[] bytes) throws IOException {
        if (maxDepth(bytes) > 200) throw new IOException("JSON nesting too deep");
        return MAPPER.readTree(bytes);
    }

    /** Cheap nesting-depth scan so hostile input cannot overflow the recursive parser. */
    static int maxDepth(byte[] b) {
        int depth = 0, max = 0;
        boolean inStr = false, esc = false;
        for (byte x : b) {
            if (inStr) {
                if (esc) esc = false;
                else if (x == '\\') esc = true;
                else if (x == '"') inStr = false;
                continue;
            }
            if (x == '"') inStr = true;
            else if (x == '{' || x == '[') { if (++depth > max) max = depth; }
            else if (x == '}' || x == ']') depth--;
        }
        return max;
    }

    public static Instant instant(JsonNode n) {
        if (n == null || !n.isTextual()) return null;
        try { return Instant.parse(n.asText().trim()); }
        catch (DateTimeParseException e) { return null; }
    }
}
