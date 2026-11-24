package com.ashford.terminal.http;

import com.ashford.terminal.config.AppConfig;
import com.ashford.terminal.service.ApiException;
import com.ashford.terminal.service.ProcessingService;
import com.ashford.terminal.upstream.GroqProxy;
import com.ashford.terminal.upstream.NewsProxy;
import com.ashford.terminal.upstream.UpstreamResponse;
import com.ashford.terminal.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP surface (JDK built-in server, no framework). Security posture:
 *  - binds to loopback by default;
 *  - rejects requests whose Host header is not allow-listed (DNS-rebinding defence) or whose Origin is not allow-listed
 *    (so another website cannot spend the server-side API keys);
 *  - POST bodies must be application/json and are size-limited;
 *  - errors never include stack traces or secrets.
 */
public final class ApiServer {
    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private final AppConfig cfg;
    private final ProcessingService svc;
    private final GroqProxy groq;
    private final NewsProxy news;
    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> { Thread t = new Thread(r, "api"); t.setDaemon(true); return t; });

    public ApiServer(AppConfig cfg, ProcessingService svc, GroqProxy groq, NewsProxy news) throws IOException {
        this.cfg = cfg; this.svc = svc; this.groq = groq; this.news = news;
        this.server = HttpServer.create(new InetSocketAddress(cfg.host(), cfg.port()), 0);
        server.setExecutor(pool);
        server.createContext("/api/health", wrap("GET", this::health));
        server.createContext("/api/process", wrap("POST", ex -> json(ex, 200, svc.process(body(ex)))));
        server.createContext("/api/reevaluate", wrap("POST", ex -> json(ex, 200, svc.reevaluate(body(ex)))));
        server.createContext("/api/score", wrap("POST", ex -> json(ex, 200, svc.score(body(ex)))));
        server.createContext("/api/groq/chat", wrap("POST", ex -> relay(ex, groq.chat(body(ex)))));
        server.createContext("/api/news/search", wrap("GET", ex -> relay(ex, news.search(query(ex, "q")))));
        server.createContext("/", wrap("GET", this::staticFile));
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); pool.shutdownNow(); }
    public int port() { return server.getAddress().getPort(); }

    // ------------------------------------------------------------------ plumbing

    private com.sun.net.httpserver.HttpHandler wrap(String method, Handler h) {
        return ex -> {
            long t0 = System.nanoTime();
            int status = 500;
            try {
                guard(ex);
                cors(ex);
                if (ex.getRequestMethod().equals("OPTIONS")) { status = 204; ex.sendResponseHeaders(204, -1); return; }
                if (!ex.getRequestMethod().equals(method)) throw new ApiException(405, "method_not_allowed", "Use " + method + " for this endpoint.");
                h.handle(ex);
                status = ex.getResponseCode();
            } catch (ApiException e) {
                status = e.status; error(ex, e.status, e.code, e.getMessage());
            } catch (Throwable t) {
                status = 500;
                System.err.println(Instant.now() + " ERROR " + ex.getRequestURI().getPath() + " " + t.getClass().getSimpleName());
                error(ex, 500, "internal_error", "Unexpected server error.");
            } finally {
                System.out.printf("%s %s %s -> %d (%d ms)%n", Instant.now(), ex.getRequestMethod(), ex.getRequestURI().getPath(), status, (System.nanoTime() - t0) / 1_000_000);
                ex.close();
            }
        };
    }

    private void guard(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !cfg.allowedHosts().contains(host.toLowerCase(Locale.ROOT)))
            throw new ApiException(403, "host_not_allowed", "Host header is not allowed. Set ALLOWED_HOSTS to permit it.");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !cfg.allowedOrigins().contains(origin))
            throw new ApiException(403, "origin_not_allowed", "Origin is not allowed. Set ALLOWED_ORIGINS to permit it.");
    }

    private void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin);
        h.set("Vary", "Origin");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Expose-Headers", "retry-after, x-ratelimit-limit-tokens, x-ratelimit-remaining-tokens");
        h.set("Access-Control-Max-Age", "600");
    }

    private JsonNode body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new ApiException(415, "unsupported_media_type", "Content-Type must be application/json.");
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl != null) {
            try { if (Long.parseLong(cl.trim()) > cfg.maxBodyBytes()) throw new ApiException(413, "payload_too_large", "Request body too large."); }
            catch (NumberFormatException e) { throw new ApiException(400, "invalid_request", "Bad Content-Length."); }
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > cfg.maxBodyBytes()) throw new ApiException(413, "payload_too_large", "Request body too large.");
            }
        }
        if (buf.size() == 0) throw new ApiException(400, "invalid_json", "Empty request body.");
        try { return Json.parse(buf.toByteArray()); }
        catch (IOException e) { throw new ApiException(400, "invalid_json", "Request body is not valid JSON."); }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(name)) return URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void json(HttpExchange ex, int status, JsonNode node) throws IOException {
        send(ex, status, "application/json; charset=utf-8", node.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange ex, int status, String code, String message) {
        try {
            ObjectNode o = Json.obj();
            ObjectNode e = o.putObject("error");
            e.put("code", code); e.put("message", message);
            json(ex, status, o);
        } catch (Exception ignored) { /* response already started or connection gone */ }
    }

    private static void relay(HttpExchange ex, UpstreamResponse r) throws IOException {
        r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        send(ex, r.status(), r.contentType(), r.body());
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
    }

    // ------------------------------------------------------------------ endpoints

    private void health(HttpExchange ex) throws IOException {
        ObjectNode o = Json.obj();
        o.put("ok", true);
        o.put("apiVersion", ProcessingService.API_VERSION);
        o.put("service", "ashford-terminal-backend");
        o.put("groqConfigured", cfg.groqConfigured());
        o.put("newsConfigured", cfg.newsConfigured());
        ObjectNode s = o.putObject("staleAfterMinutes");
        s.put("fast", svc.staleness().fastMinutes()); s.put("slow", svc.staleness().slowMinutes());
        json(ex, 200, o);
    }

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8",
            "json", "application/json", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    private void staticFile(HttpExchange ex) throws IOException {
        String path = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if (path.equals("/")) path = "/index.html";
        Path root = cfg.staticDir().toAbsolutePath().normalize();
        Path f = root.resolve(path.substring(1)).normalize();
        if (!f.startsWith(root) || !Files.isRegularFile(f)) throw new ApiException(404, "not_found", "Not found.");
        String name = f.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(f));
    }
}
