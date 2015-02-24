package com.ericguerra.loglens.pipeline;

import com.ericguerra.loglens.alert.AlertEngine;
import com.ericguerra.loglens.alert.AlertRule;
import com.ericguerra.loglens.broker.InMemoryLogQueue;
import com.ericguerra.loglens.enrich.Enrichers;
import com.ericguerra.loglens.model.LogEvent;
import com.ericguerra.loglens.parse.LineParser;
import com.ericguerra.loglens.sink.Sink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
    private final Clock clock = Clock.fixed(Instant.parse("2014-06-02T12:00:00Z"), ZoneOffset.UTC);

    /** Accepts everything except events whose message contains "poison". */
    static final class CapturingSink implements Sink {
        final List<LogEvent> stored = new CopyOnWriteArrayList<>();

        @Override
        public Result write(List<LogEvent> events) {
            List<Failed> failed = new ArrayList<>();
            int written = 0;
            for (LogEvent e : events) {
                if (e.raw().contains("poison")) {
                    failed.add(new Failed(e, "mapper_parsing_exception"));
                } else {
                    stored.add(e);
                    written++;
                }
            }
            return new Result(written, failed);
        }
    }

    @Test
    void endToEndBatchWithAlertsAndDeadLetters() throws Exception {
        var queue = new InMemoryLogQueue();
        var sink = new CapturingSink();
        List<AlertEngine.Alert> alerts = new ArrayList<>();
        var engine = new AlertEngine(List.of(new AlertRule("5xx", AlertRule.fieldEquals(Map.of("http.response.status_class", "5xx")),
                2, null, Duration.ofMinutes(1), Duration.ofMinutes(5), "critical")), alerts::add, clock);
        var pipeline = new Pipeline(queue, new LineParser(), Enrichers.defaults(), engine, sink, "dead", 100, clock);

        String nginx = "198.51.100.1 - - [02/Jun/2014:11:59:00 +0000] \"GET /checkout?token=abc HTTP/1.1\" %d 10 \"-\" \"curl/7.30\"";
        pipeline.process(List.of(
                "{\"message\":\"" + String.format(nginx, 500).replace("\"", "\\\"") + "\",\"source\":\"/var/log/nginx/access.log\"}",
                String.format(nginx, 502),
                String.format(nginx, 200),
                "poison line that the index rejects"));

        assertEquals(3, sink.stored.size());
        LogEvent first = sink.stored.get(0);
        assertEquals("/var/log/nginx/access.log", first.source());
        assertEquals("5xx", first.get("http.response.status_class"));
        assertEquals("/checkout?token=[REDACTED]", first.get("url.original"));
        assertEquals("bot", first.get("user_agent.name"));
        assertNull(first.get("http.request.referrer"), "quoted \"-\" is treated as empty");

        assertEquals(1, alerts.size(), "two 5xx in the window crosses the threshold");
        assertEquals(1, queue.list("dead").size());
        assertTrue(queue.list("dead").get(0).contains("mapper_parsing_exception"));

        var m = pipeline.metrics().snapshot();
        assertEquals(4L, m.get("received"));
        assertEquals(1L, m.get("parse_failures"));
        assertEquals(1L, m.get("dead_lettered"));
    }

    @Test
    void workersDrainTheQueue() throws Exception {
        var queue = new InMemoryLogQueue();
        var sink = new CapturingSink();
        for (int i = 0; i < 500; i++) queue.offer("2014-06-02T11:00:00Z INFO com.acme.Job - tick " + i);
        try (var pipeline = new Pipeline(queue, new LineParser(), Enrichers.defaults(), null, sink, "dead", 50, clock)) {
            pipeline.start(3);
            long deadline = System.currentTimeMillis() + 5000;
            while (sink.stored.size() < 500 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        }
        assertEquals(500, sink.stored.size());
        assertTrue(sink.stored.stream().allMatch(e -> "app_log".equals(e.get("event.dataset"))));
    }
}
