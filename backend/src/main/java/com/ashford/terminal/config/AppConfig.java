package com.ashford.terminal.config;

import java.nio.file.Path;
import java.util.*;

/**
 * All runtime configuration, read from environment variables (never from files shipped to the browser).
 * Secrets are only ever held here and in the upstream proxies; toString() redacts them.
 */
public record AppConfig(
        String host, int port, Path staticDir, Set<String> allowedOrigins, Set<String> allowedHosts,
        String groqApiKey, String groqUrl, String groqModel, int groqTimeoutSec, int groqMaxTokens,
        String newsApiKey, String newsBaseUrl, String newsCategory, int newsTimeoutSec,
        long staleFastMinutes, long staleSlowMinutes, int maxBodyBytes, Set<String> disabledRules) {

    public boolean groqConfigured() { return groqApiKey != null && !groqApiKey.isBlank(); }
    public boolean newsConfigured() { return newsApiKey != null && !newsApiKey.isBlank(); }

    public static AppConfig fromEnv(Map<String, String> env) {
        String host = get(env, "BIND_HOST", "127.0.0.1");
        int port = intOf(env, "PORT", 8080);
        Set<String> origins = new LinkedHashSet<>(List.of("http://localhost:" + port, "http://127.0.0.1:" + port, "http://[::1]:" + port));
        origins.addAll(csv(env.get("ALLOWED_ORIGINS")));
        Set<String> hosts = new LinkedHashSet<>(List.of("localhost:" + port, "127.0.0.1:" + port, "[::1]:" + port));
        if (!host.equals("0.0.0.0") && !host.equals("127.0.0.1") && !host.equals("localhost")) hosts.add(host + ":" + port);
        hosts.addAll(csv(env.get("ALLOWED_HOSTS")));
        String dir = env.get("STATIC_DIR");
        return new AppConfig(host, port,
                dir == null || dir.isBlank() ? Path.of("../frontend") : Path.of(dir),
                Set.copyOf(origins), Set.copyOf(hosts),
                trim(env.get("GROQ_API_KEY")), get(env, "GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions"),
                trim(env.get("GROQ_MODEL")), intOf(env, "GROQ_TIMEOUT_SECONDS", 40), intOf(env, "GROQ_MAX_COMPLETION_TOKENS", 8192),
                trim(env.get("NEWSDATA_API_KEY")), get(env, "NEWSDATA_BASE_URL", "https://newsdata.io/api/1"),
                get(env, "NEWS_CATEGORY", "business,politics"), intOf(env, "NEWS_TIMEOUT_SECONDS", 20),
                intOf(env, "STALE_AFTER_MINUTES", 720), intOf(env, "STALE_AFTER_MINUTES_SLOW", 10080),
                intOf(env, "MAX_BODY_BYTES", 6_000_000), Set.copyOf(csv(env.get("CONSISTENCY_DISABLED_RULES"))));
    }

    @Override public String toString() {
        return "AppConfig[host=" + host + ", port=" + port + ", staticDir=" + staticDir + ", groqConfigured=" + groqConfigured()
                + ", newsConfigured=" + newsConfigured() + ", staleFast=" + staleFastMinutes + ", staleSlow=" + staleSlowMinutes + "]";
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static String get(Map<String, String> env, String k, String d) { String v = env.get(k); return v == null || v.isBlank() ? d : v.trim(); }
    private static int intOf(Map<String, String> env, String k, int d) {
        String v = env.get(k);
        if (v == null || v.isBlank()) return d;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(k + " must be an integer, got: " + v); }
    }
    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }
}
