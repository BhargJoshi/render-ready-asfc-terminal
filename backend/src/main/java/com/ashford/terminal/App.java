package com.ashford.terminal;

import com.ashford.terminal.config.AppConfig;
import com.ashford.terminal.http.ApiServer;
import com.ashford.terminal.reconcile.Staleness;
import com.ashford.terminal.service.ProcessingService;
import com.ashford.terminal.upstream.GroqProxy;
import com.ashford.terminal.upstream.NewsProxy;

import java.time.Clock;

/** Entry point. Configuration comes exclusively from environment variables (see README.md). */
public final class App {
    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.fromEnv(System.getenv());
        ProcessingService svc = new ProcessingService(Clock.systemUTC(),
                new Staleness(cfg.staleFastMinutes(), cfg.staleSlowMinutes()), cfg.disabledRules());
        ApiServer server = new ApiServer(cfg, svc, new GroqProxy(cfg), new NewsProxy(cfg));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Ashford Terminal backend listening on http://" + cfg.host() + ":" + server.port());
        System.out.println(cfg);
        if (!cfg.groqConfigured()) System.out.println("WARNING: GROQ_API_KEY not set - AI extraction/assistant disabled; terminal will use its local parser.");
        if (!cfg.newsConfigured()) System.out.println("WARNING: NEWSDATA_API_KEY not set - live news search disabled.");
    }
}
