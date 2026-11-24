package com.ashford.terminal.http;

import com.ashford.terminal.config.AppConfig;
import com.ashford.terminal.reconcile.Staleness;
import com.ashford.terminal.service.ProcessingService;
import com.ashford.terminal.upstream.GroqProxy;
import com.ashford.terminal.upstream.NewsProxy;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.ashford.terminal.service.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiServerTest {
    static HttpServer mock;                       // stands in for Groq + newsdata
    static final AtomicReference<String> lastGroqAuth = new AtomicReference<>();
    static final AtomicReference<String> lastGroqBody = new AtomicReference<>();
    static final AtomicReference<String> lastNewsQuery = new AtomicReference<>();
    static volatile int groqStatus = 200;
    static volatile int groqDelayMs = 0;
    static ApiServer withKeys, withoutKeys;
    static int portA, portB, mockPort;
    static Path staticDir;
    static final HttpClient client = HttpClient.newHttpClient();

    static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    static ApiServer start(int port, Map<String, String> extra) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("PORT", String.valueOf(port), "STATIC_DIR", staticDir.toString()));
        env.putAll(extra);
        AppConfig cfg = AppConfig.fromEnv(env);
        ApiServer s = new ApiServer(cfg, new ProcessingService(Clock.systemUTC(), new Staleness(720, 10080), cfg.disabledRules()), new GroqProxy(cfg), new NewsProxy(cfg));
        s.start();
        return s;
    }

    @BeforeAll static void up() throws Exception {
        staticDir = Files.createTempDirectory("static");
        Files.writeString(staticDir.resolve("index.html"), "<html>terminal</html>");
        Files.writeString(staticDir.resolve("secret.txt"), "x");
        mockPort = freePort();
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", mockPort), 0);
        mock.createContext("/groq", ex -> {
            lastGroqAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastGroqBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { if (groqDelayMs > 0) Thread.sleep(groqDelayMs); } catch (InterruptedException ignored) {}
            byte[] b = (groqStatus == 200 ? "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}" : "{\"error\":{\"message\":\"rate limit\",\"code\":\"rate_limit_exceeded\"}}").getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("x-ratelimit-limit-tokens", "6000");
            ex.getResponseHeaders().set("retry-after", "7");
            ex.sendResponseHeaders(groqStatus, b.length); ex.getResponseBody().write(b); ex.close();
        });
        mock.createContext("/news/latest", ex -> {
            lastNewsQuery.set(ex.getRequestURI().getRawQuery());
            byte[] b = "{\"status\":\"success\",\"results\":[]}".getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        mock.start();
        portA = freePort(); portB = freePort();
        withKeys = start(portA, Map.of("GROQ_API_KEY", "test-secret-key", "GROQ_API_URL", "http://127.0.0.1:" + mockPort + "/groq",
                "NEWSDATA_API_KEY", "news-secret", "NEWSDATA_BASE_URL", "http://127.0.0.1:" + mockPort + "/news", "GROQ_TIMEOUT_SECONDS", "2"));
        withoutKeys = start(portB, Map.of());
    }

    @AfterAll static void down() { withKeys.stop(); withoutKeys.stop(); mock.stop(0); }

    private static HttpResponse<String> call(int port, String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        headers.forEach((k, v) -> { if (!k.equalsIgnoreCase("Host")) b.header(k, v); });
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(int p, String path, String body) throws Exception { return call(p, "POST", path, body, Map.of()); }

    private static final String CHAT = "{\"model\":\"openai/gpt-oss-20b\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_completion_tokens\":999999,\"tools\":[{\"x\":1}],\"stream\":false}";

    @Test void healthReportsConfigurationButNeverSecrets() throws Exception {
        HttpResponse<String> r = call(portA, "GET", "/api/health", null, Map.of());
        assertEquals(200, r.statusCode());
        JsonNode j = Json.MAPPER.readTree(r.body());
        assertTrue(j.get("groqConfigured").asBoolean());
        assertFalse(r.body().contains("test-secret-key")); assertFalse(r.body().contains("news-secret"));
        assertFalse(Json.MAPPER.readTree(call(portB, "GET", "/api/health", null, Map.of()).body()).get("groqConfigured").asBoolean());
    }

    @Test void groqRelayInjectsKeyServerSideAllowListsFieldsCapsTokensAndRelaysHeaders() throws Exception {
        groqStatus = 200;
        HttpResponse<String> r = post(portA, "/api/groq/chat", CHAT);
        assertEquals(200, r.statusCode());
        assertEquals("Bearer test-secret-key", lastGroqAuth.get());
        JsonNode sent = Json.MAPPER.readTree(lastGroqBody.get());
        assertFalse(sent.has("tools"), "unlisted fields are not forwarded");
        assertEquals(8192, sent.get("max_completion_tokens").asInt(), "token cap enforced");
        assertEquals("6000", r.headers().firstValue("x-ratelimit-limit-tokens").orElse(""));
        assertFalse(r.body().contains("test-secret-key"));
    }

    @Test void upstreamErrorsAreRelayedWithStatusSoBrowserRetryLogicStillWorks() throws Exception {
        groqStatus = 429;
        HttpResponse<String> r = post(portA, "/api/groq/chat", CHAT);
        groqStatus = 200;
        assertEquals(429, r.statusCode());
        assertEquals("7", r.headers().firstValue("retry-after").orElse(""));
        assertEquals("rate_limit_exceeded", Json.MAPPER.readTree(r.body()).get("error").get("code").asText());
    }

    @Test void groqUnavailableCases() throws Exception {
        // no key configured
        HttpResponse<String> r = post(portB, "/api/groq/chat", CHAT);
        assertEquals(503, r.statusCode());
        assertEquals("groq_not_configured", Json.MAPPER.readTree(r.body()).get("error").get("code").asText());
        // upstream too slow (timeout is 2s)
        groqDelayMs = 3500;
        r = post(portA, "/api/groq/chat", CHAT);
        groqDelayMs = 0;
        assertEquals(504, r.statusCode());
        assertEquals("upstream_timeout", Json.MAPPER.readTree(r.body()).get("error").get("code").asText());
    }

    @Test void groqUnreachableIsReportedAs502() throws Exception {
        int dead = freePort(), port = freePort();
        ApiServer s = start(port, Map.of("GROQ_API_KEY", "k", "GROQ_API_URL", "http://127.0.0.1:" + dead + "/x"));
        try {
            HttpResponse<String> r = post(port, "/api/groq/chat", CHAT);
            assertEquals(502, r.statusCode());
            assertEquals("upstream_unreachable", Json.MAPPER.readTree(r.body()).get("error").get("code").asText());
        } finally { s.stop(); }
    }

    @Test void invalidChatRequestsAreRejected() throws Exception {
        assertEquals(400, post(portA, "/api/groq/chat", "{\"model\":\"m\",\"messages\":[]}").statusCode());
        assertEquals(400, post(portA, "/api/groq/chat", "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"stream\":true}").statusCode());
        assertEquals(400, post(portA, "/api/groq/chat", "not json").statusCode());
    }

    @Test void newsRelayAddsKeyAndCategoryServerSideAndReportsUnconfigured() throws Exception {
        HttpResponse<String> r = call(portA, "GET", "/api/news/search?q=gold%20price", null, Map.of());
        assertEquals(200, r.statusCode());
        assertTrue(lastNewsQuery.get().contains("apikey=news-secret"));
        assertTrue(lastNewsQuery.get().contains("q=gold+price"));
        assertTrue(lastNewsQuery.get().contains("category=business%2Cpolitics"));
        assertFalse(r.body().contains("news-secret"));
        HttpResponse<String> u = call(portB, "GET", "/api/news/search?q=gold", null, Map.of());
        assertEquals(503, u.statusCode());
        assertEquals("error", Json.MAPPER.readTree(u.body()).get("status").asText());
        assertEquals(400, call(portA, "GET", "/api/news/search", null, Map.of()).statusCode());
    }

    @Test void foreignOriginsAreRejectedSoOtherSitesCannotSpendKeys() throws Exception {
        HttpResponse<String> r = call(portA, "POST", "/api/groq/chat", CHAT, Map.of("Origin", "https://evil.example"));
        assertEquals(403, r.statusCode());
        assertEquals(200, call(portA, "POST", "/api/groq/chat", CHAT, Map.of("Origin", "http://localhost:" + portA)).statusCode());
        assertEquals(403, call(portA, "GET", "/api/news/search?q=x", null, Map.of("Origin", "null")).statusCode());
    }

    @Test void postsRequireJsonContentType() throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + portA + "/api/process"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415, client.send(rq, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void processEndpointRoundTripAndMalformedBodies() throws Exception {
        var req = request(goodFresh(), "groq", "ok", "ok");
        req.put("sourceText", SOURCE);
        HttpResponse<String> r = post(portA, "/api/process", req.toString());
        assertEquals(200, r.statusCode());
        JsonNode j = Json.MAPPER.readTree(r.body());
        assertEquals("Bullish", j.get("dataset").get("bias").get("gold").get("b").asText());
        assertTrue(j.get("score").get("value").isInt());
        assertEquals("UPDATED", j.get("integrity").get("fields").get("macro.fed").get("status").asText());
        assertEquals(400, post(portA, "/api/process", "{").statusCode());
        assertEquals(400, post(portA, "/api/process", "[]").statusCode());
        assertEquals(400, post(portA, "/api/process", "[".repeat(5000) + "]".repeat(5000)).statusCode());
        assertEquals(405, call(portA, "GET", "/api/process", null, Map.of()).statusCode());
    }

    @Test void scoreEndpointUsesLegacyFormula() throws Exception {
        HttpResponse<String> r = post(portA, "/api/score", "{\"bias\":{\"gold\":{\"b\":\"Bullish\",\"cf\":80},\"btc\":{\"b\":\"Bearish\",\"cf\":20}}}");
        assertEquals(60, Json.MAPPER.readTree(r.body()).get("score").get("value").asInt());   // (0.8-0.2)/(1.0)*100
    }

    @Test void staticFilesAreServedAndTraversalIsBlocked() throws Exception {
        assertEquals("<html>terminal</html>", call(portA, "GET", "/", null, Map.of()).body());
        assertEquals(404, call(portA, "GET", "/..%2F..%2Fetc%2Fpasswd", null, Map.of()).statusCode());
        assertEquals(404, call(portA, "GET", "/nope.html", null, Map.of()).statusCode());
    }

    @Test void oversizedBodyIsRejected() throws Exception {
        int port = freePort();
        ApiServer s = start(port, Map.of("MAX_BODY_BYTES", "1000"));
        try { assertEquals(413, post(port, "/api/process", "{\"x\":\"" + "a".repeat(5000) + "\"}").statusCode()); }
        finally { s.stop(); }
    }
}
