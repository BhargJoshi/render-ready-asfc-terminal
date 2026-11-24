package com.ashford.terminal.upstream;

import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A response to relay to the browser: status, body and a small allow-list of headers. */
public record UpstreamResponse(int status, byte[] body, String contentType, Map<String, String> headers) {

    /** Error body shaped like the provider's own (so the unchanged browser error handling still reads it). */
    public static UpstreamResponse groqError(int status, String code, String message) {
        ObjectNode e = Json.obj();
        ObjectNode err = e.putObject("error");
        err.put("message", message); err.put("type", "backend_error"); err.put("code", code);
        return new UpstreamResponse(status, e.toString().getBytes(StandardCharsets.UTF_8), "application/json", new LinkedHashMap<>());
    }

    public static UpstreamResponse newsError(int status, String code, String message) {
        ObjectNode e = Json.obj();
        e.put("status", "error");
        ObjectNode r = e.putObject("results");
        r.put("message", message); r.put("code", code);
        return new UpstreamResponse(status, e.toString().getBytes(StandardCharsets.UTF_8), "application/json", new LinkedHashMap<>());
    }
}
