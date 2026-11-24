package com.ashford.terminal.upstream;

import com.ashford.terminal.config.AppConfig;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side relay to Groq's OpenAI-compatible chat endpoint. The browser keeps building prompts, schemas and
 * running its retry cascade exactly as before; only the HTTP hop moved here so the API key never leaves the server.
 *
 * Hardening: only a fixed set of request fields is forwarded (no tools / streaming), max_completion_tokens is
 * capped, the model can be pinned server-side, and upstream status codes + rate-limit headers are relayed
 * unchanged so the browser's existing 401/404/429 handling and token-budget learning keep working.
 */
public final class GroqProxy {
    private static final Set<String> FORWARDED_FIELDS = Set.of(
            "messages", "model", "temperature", "max_completion_tokens", "response_format", "reasoning_effort", "include_reasoning");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._/:-]{1,80}");

    private final AppConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public GroqProxy(AppConfig cfg) { this.cfg = cfg; }

    public UpstreamResponse chat(JsonNode req) {
        if (!cfg.groqConfigured())
            return UpstreamResponse.groqError(503, "groq_not_configured", "GROQ_API_KEY is not set on the backend, so AI extraction and the assistant are unavailable.");
        if (req == null || !req.isObject() || !req.path("messages").isArray() || req.path("messages").isEmpty())
            return UpstreamResponse.groqError(400, "invalid_request", "\"messages\" must be a non-empty array.");
        if (req.path("stream").asBoolean(false))
            return UpstreamResponse.groqError(400, "invalid_request", "Streaming is not supported by this relay.");

        ObjectNode body = Json.obj();
        req.fields().forEachRemaining(e -> { if (FORWARDED_FIELDS.contains(e.getKey())) body.set(e.getKey(), e.getValue()); });
        String model = cfg.groqModel() != null ? cfg.groqModel() : Json.text(req, "model");
        if (!MODEL_ID.matcher(model).matches())
            return UpstreamResponse.groqError(400, "invalid_request", "A valid \"model\" is required (or set GROQ_MODEL on the backend).");
        body.put("model", model);
        int max = req.path("max_completion_tokens").asInt(1024);
        body.put("max_completion_tokens", Math.max(1, Math.min(max, cfg.groqMaxTokens())));

        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(cfg.groqUrl()))
                    .timeout(Duration.ofSeconds(cfg.groqTimeoutSec()))
                    .header("Authorization", "Bearer " + cfg.groqApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> headers = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> {
                String lk = k.toLowerCase();
                if (!v.isEmpty() && (lk.startsWith("x-ratelimit-") || lk.equals("retry-after"))) headers.put(lk, v.get(0));
            });
            String ct = resp.headers().firstValue("content-type").orElse("application/json");
            return new UpstreamResponse(resp.statusCode(), resp.body(), ct, headers);
        } catch (HttpTimeoutException e) {
            return UpstreamResponse.groqError(504, "upstream_timeout", "Groq did not respond within " + cfg.groqTimeoutSec() + "s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UpstreamResponse.groqError(502, "upstream_unreachable", "Request to Groq was interrupted.");
        } catch (IOException | IllegalArgumentException e) {
            return UpstreamResponse.groqError(502, "upstream_unreachable", "Could not reach Groq from the backend.");
        }
    }
}
