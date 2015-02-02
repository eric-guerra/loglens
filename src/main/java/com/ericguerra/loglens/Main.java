package com.ericguerra.loglens;

import com.ericguerra.loglens.alert.AlertEngine;
import com.ericguerra.loglens.alert.AlertRule;
import com.ericguerra.loglens.alert.WebhookNotifier;
import com.ericguerra.loglens.broker.RedisLogQueue;
import com.ericguerra.loglens.enrich.Enrichers;
import com.ericguerra.loglens.parse.LineParser;
import com.ericguerra.loglens.pipeline.Pipeline;
import com.ericguerra.loglens.sink.ElasticsearchBulkSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String redisUri = env.getOrDefault("REDIS_URL", "redis://localhost:6379");
        String key = env.getOrDefault("LOGLENS_QUEUE", "logs:incoming");
        var queue = new RedisLogQueue(redisUri, key);

        var sink = new ElasticsearchBulkSink(env.getOrDefault("ES_URL", "http://localhost:9200"),
                env.getOrDefault("ES_INDEX_PREFIX", "logs"), env.get("ES_USER"), env.get("ES_PASSWORD"), 5, 200);

        Consumer<AlertEngine.Alert> notifier = env.containsKey("ALERT_WEBHOOK_URL")
                ? new WebhookNotifier(env.get("ALERT_WEBHOOK_URL"))
                : alert -> log.warn("ALERT {}", alert.summary());
        var rules = List.of(
                new AlertRule("http-5xx-rate", AlertRule.fieldEquals(Map.of("http.response.status_class", "5xx")),
                        20, 0.05, Duration.ofMinutes(1), Duration.ofMinutes(10), "critical"),
                new AlertRule("app-errors", AlertRule.fieldEquals(Map.of("log.level", "error")),
                        50, null, Duration.ofMinutes(5), Duration.ofMinutes(15), "warning"),
                new AlertRule("fatal", AlertRule.fieldEquals(Map.of("log.level", "fatal")),
                        1, null, Duration.ofMinutes(1), Duration.ofMinutes(5), "critical"));
        var alerts = new AlertEngine(rules, notifier, Clock.systemUTC());

        var pipeline = new Pipeline(queue, new LineParser(), Enrichers.defaults(), alerts, sink,
                key + ":dead", Integer.parseInt(env.getOrDefault("LOGLENS_BATCH", "500")), Clock.systemUTC());
        pipeline.start(Integer.parseInt(env.getOrDefault("LOGLENS_WORKERS", "4")));

        // Ops endpoints: /health and /metrics (JSON) for the dashboard or a Prometheus JSON exporter.
        HttpServer ops = HttpServer.create(new InetSocketAddress(Integer.parseInt(env.getOrDefault("PORT", "9100"))), 0);
        ObjectMapper json = new ObjectMapper();
        ops.createContext("/health", ex -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        ops.createContext("/metrics", ex -> {
            Map<String, Object> m = new LinkedHashMap<>(pipeline.metrics().snapshot());
            m.put("queue_depth", queue.depth());
            byte[] body = json.writeValueAsBytes(m);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        ops.start();
        log.info("LogLens consuming {} from {}", key, redisUri);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pipeline.close();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ops.stop(0);
            queue.close();
        }));
    }
}
