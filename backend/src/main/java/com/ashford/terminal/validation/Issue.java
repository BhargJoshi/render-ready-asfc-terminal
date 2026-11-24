package com.ashford.terminal.validation;

import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One validation finding. severity: "info" (normalised/benign), "warn" (value kept but questionable),
 * "error" (value rejected).
 */
public record Issue(String code, String severity, String path, String message) {
    public static Issue info(String code, String path, String msg) { return new Issue(code, "info", path, msg); }
    public static Issue warn(String code, String path, String msg) { return new Issue(code, "warn", path, msg); }
    public static Issue error(String code, String path, String msg) { return new Issue(code, "error", path, msg); }

    public ObjectNode toJson() {
        ObjectNode o = Json.obj();
        o.put("code", code); o.put("severity", severity); o.put("path", path); o.put("message", message);
        return o;
    }
}
