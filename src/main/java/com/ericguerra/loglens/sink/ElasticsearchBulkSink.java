package com.ericguerra.loglens.sink;

import com.ericguerra.loglens.model.LogEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes events with the _bulk API into daily indices (logs-YYYY.MM.DD).
 *
 * Bulk responses can partially fail. Per-item 429/5xx errors are retried with exponential
 * backoff and jitter; 4xx mapping errors are permanent and go straight to the dead-letter list.
 */
public final class ElasticsearchBulkSink implements Sink {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchBulkSink.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI bulkUri;
    private final String indexPrefix;
    private final String authHeader;
    private final int maxRetries;
    private final long baseBackoffMs;

    public ElasticsearchBulkSink(String baseUrl, String indexPrefix, String username, String password, int maxRetries, long baseBackoffMs) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.bulkUri = URI.create(baseUrl.replaceAll("/$", "") + "/_bulk");
        this.indexPrefix = indexPrefix;
        this.authHeader = username == null ? null
                : "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
    }

    String indexFor(LogEvent e) {
        return indexPrefix + "-" + DAY.format(e.timestamp());
    }

    @Override
    public Result write(List<LogEvent> events) throws InterruptedException {
        List<LogEvent> pending = new ArrayList<>(events);
        List<Failed> failed = new ArrayList<>();
        int written = 0;

        for (int attempt = 0; !pending.isEmpty(); attempt++) {
            if (attempt > 0) {
                long backoff = baseBackoffMs * (1L << Math.min(attempt - 1, 6));
                Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff / 2 + 1));
            }
            BulkOutcome outcome;
            try {
                outcome = send(pending);
            } catch (IOException e) {
                log.warn("bulk request failed (attempt {}): {}", attempt + 1, e.toString());
                outcome = new BulkOutcome(0, List.of(), pending.stream().map(ev -> new Failed(ev, e.toString())).toList());
            }
            written += outcome.ok();
            failed.addAll(outcome.permanent());
            if (attempt >= maxRetries) {
                failed.addAll(outcome.retryable());
                break;
            }
            pending = outcome.retryable().stream().map(Failed::event).toList();
        }
        return new Result(written, failed);
    }

    private record BulkOutcome(int ok, List<Failed> permanent, List<Failed> retryable) {}

    private BulkOutcome send(List<LogEvent> events) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder(events.size() * 512);
        for (LogEvent e : events) {
            body.append(JSON.writeValueAsString(Map.of("index", Map.of("_index", indexFor(e))))).append('\n');
            body.append(JSON.writeValueAsString(e.toDocument())).append('\n');
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(bulkUri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authHeader != null) req.header("Authorization", authHeader);

        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
            return new BulkOutcome(0, List.of(), events.stream().map(ev -> new Failed(ev, "HTTP " + resp.statusCode())).toList());
        }
        if (resp.statusCode() >= 400) {
            return new BulkOutcome(0, events.stream().map(ev -> new Failed(ev, "HTTP " + resp.statusCode() + ": " + truncate(resp.body()))).toList(), List.of());
        }

        JsonNode root = JSON.readTree(resp.body());
        if (!root.path("errors").asBoolean(false)) return new BulkOutcome(events.size(), List.of(), List.of());

        int ok = 0;
        List<Failed> permanent = new ArrayList<>();
        List<Failed> retryable = new ArrayList<>();
        JsonNode items = root.path("items");
        for (int i = 0; i < events.size(); i++) {
            JsonNode item = items.path(i).path("index");
            int status = item.path("status").asInt(500);
            if (status < 300) {
                ok++;
            } else {
                String reason = item.path("error").path("type").asText("unknown") + ": " + item.path("error").path("reason").asText("");
                (status == 429 || status >= 500 ? retryable : permanent).add(new Failed(events.get(i), reason));
            }
        }
        return new BulkOutcome(ok, permanent, retryable);
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
