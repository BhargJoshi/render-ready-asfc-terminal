package com.ashford.terminal.upstream;

import com.ashford.terminal.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * Server-side relay to newsdata.io. The provider's JSON is returned unchanged (the browser's
 * mapNewsdataArticle() / isMarketsRelevant() keep doing the mapping and relevance filtering), so only the key moved.
 */
public final class NewsProxy {
    private final AppConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public NewsProxy(AppConfig cfg) { this.cfg = cfg; }

    public UpstreamResponse search(String keyword) {
        if (!cfg.newsConfigured())
            return UpstreamResponse.newsError(503, "news_not_configured", "NEWSDATA_API_KEY is not set on the backend, so live news search is unavailable.");
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) return UpstreamResponse.newsError(400, "invalid_request", "A search keyword (q) is required.");
        if (q.length() > 100) q = q.substring(0, 100);   // provider's free-plan limit, same as the original browser code
        String url = cfg.newsBaseUrl() + "/latest?apikey=" + enc(cfg.newsApiKey()) + "&q=" + enc(q)
                + "&category=" + enc(cfg.newsCategory()) + "&language=en&size=10";
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(cfg.newsTimeoutSec())).GET().build();
            HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            return new UpstreamResponse(resp.statusCode(), resp.body(),
                    resp.headers().firstValue("content-type").orElse("application/json"), new LinkedHashMap<>());
        } catch (HttpTimeoutException e) {
            return UpstreamResponse.newsError(504, "upstream_timeout", "newsdata.io did not respond in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UpstreamResponse.newsError(502, "upstream_unreachable", "Request to newsdata.io was interrupted.");
        } catch (IOException | IllegalArgumentException e) {
            return UpstreamResponse.newsError(502, "upstream_unreachable", "Could not reach newsdata.io from the backend.");
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
