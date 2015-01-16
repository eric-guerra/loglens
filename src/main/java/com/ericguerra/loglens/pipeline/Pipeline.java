package com.ericguerra.loglens.pipeline;

import com.ericguerra.loglens.alert.AlertEngine;
import com.ericguerra.loglens.broker.LogQueue;
import com.ericguerra.loglens.enrich.Enricher;
import com.ericguerra.loglens.model.LogEvent;
import com.ericguerra.loglens.parse.LineParser;
import com.ericguerra.loglens.sink.Sink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * N workers, each looping: take batch → decode envelope → parse → enrich → alert → bulk write.
 * Events the sink permanently rejects go to a dead-letter list with the failure reason attached.
 */
public final class Pipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final class Metrics {
        public final AtomicLong received = new AtomicLong();
        public final AtomicLong indexed = new AtomicLong();
        public final AtomicLong parseFailures = new AtomicLong();
        public final AtomicLong deadLettered = new AtomicLong();
        public final AtomicLong alerts = new AtomicLong();
        public final AtomicLong batches = new AtomicLong();

        public Map<String, Long> snapshot() {
            return Map.of("received", received.get(), "indexed", indexed.get(), "parse_failures", parseFailures.get(),
                    "dead_lettered", deadLettered.get(), "alerts", alerts.get(), "batches", batches.get());
        }
    }

    private final LogQueue queue;
    private final LineParser parser;
    private final List<Enricher> enrichers;
    private final AlertEngine alerts;
    private final Sink sink;
    private final String deadLetterList;
    private final int batchSize;
    private final Clock clock;
    private final Metrics metrics = new Metrics();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService workers;

    public Pipeline(LogQueue queue, LineParser parser, List<Enricher> enrichers, AlertEngine alerts, Sink sink,
                    String deadLetterList, int batchSize, Clock clock) {
        this.queue = queue;
        this.parser = parser;
        this.enrichers = enrichers;
        this.alerts = alerts;
        this.sink = sink;
        this.deadLetterList = deadLetterList;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    public Metrics metrics() {
        return metrics;
    }

    public void start(int threads) {
        running.set(true);
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("loglens-worker-" + t.getId());
            return t;
        });
        for (int i = 0; i < threads; i++) workers.submit(this::loop);
    }

    private void loop() {
        while (running.get()) {
            try {
                List<String> raw = queue.take(batchSize, Duration.ofSeconds(1));
                if (!raw.isEmpty()) process(raw);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("worker error, continuing", e);
            }
        }
    }

    /** Processes one batch synchronously (also used directly by tests). */
    public void process(List<String> rawMessages) throws InterruptedException {
        metrics.batches.incrementAndGet();
        metrics.received.addAndGet(rawMessages.size());
        Instant now = clock.instant();
        List<LogEvent> events = new ArrayList<>(rawMessages.size());
        for (String envelope : rawMessages) {
            LogEvent e = decode(envelope, now);
            for (Enricher enricher : enrichers) {
                try {
                    enricher.enrich(e);
                } catch (RuntimeException ex) {
                    e.tag("_enrichfailure");
                }
            }
            if (e.tags().contains("_grokparsefailure")) metrics.parseFailures.incrementAndGet();
            events.add(e);
        }

        if (alerts != null) metrics.alerts.addAndGet(alerts.observe(events).size());

        Sink.Result result = sink.write(events);
        metrics.indexed.addAndGet(result.written());
        if (!result.failed().isEmpty()) {
            List<String> dead = new ArrayList<>();
            for (Sink.Failed f : result.failed()) {
                try {
                    dead.add(JSON.writeValueAsString(Map.of("message", f.event().raw(), "source", f.event().source(),
                            "error", f.reason(), "failed_at", now.toString())));
                } catch (Exception ignored) {
                    // unreachable for plain strings
                }
            }
            queue.push(deadLetterList, dead);
            metrics.deadLettered.addAndGet(dead.size());
        }
    }

    /** Shipper envelopes are JSON {"message": "...", "source": "..."}; bare lines are accepted too. */
    private LogEvent decode(String envelope, Instant now) {
        String message = envelope;
        String source = null;
        if (envelope.startsWith("{")) {
            try {
                JsonNode node = JSON.readTree(envelope);
                if (node.hasNonNull("message")) {
                    message = node.get("message").asText();
                    source = node.path("source").asText(node.path("path").asText(null));
                }
            } catch (Exception ignored) {
                // not an envelope; parse the whole thing as the line
            }
        }
        return parser.parse(message, source, now);
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        if (workers != null) {
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        }
    }
}
