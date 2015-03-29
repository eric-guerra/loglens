package com.ericguerra.loglens.alert;

import com.ericguerra.loglens.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class AlertEngineTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2014-06-02T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static List<LogEvent> events(int errors, int ok) {
        List<LogEvent> out = new ArrayList<>();
        IntStream.range(0, errors).forEach(i -> out.add(new LogEvent("GET /x 500", "t", Instant.EPOCH).put("http.response.status_class", "5xx")));
        IntStream.range(0, ok).forEach(i -> out.add(new LogEvent("GET /x 200", "t", Instant.EPOCH).put("http.response.status_class", "2xx")));
        return out;
    }

    @Test
    void slidingWindowExpiresOldBuckets() {
        var c = new SlidingWindowCounter(Duration.ofSeconds(60));
        Instant t0 = Instant.parse("2014-06-02T12:00:00Z");
        c.add(t0, 5);
        c.add(t0.plusSeconds(30), 3);
        assertEquals(8, c.total(t0.plusSeconds(30)));
        assertEquals(3, c.total(t0.plusSeconds(61)));
        assertEquals(0, c.total(t0.plusSeconds(200)));
    }

    @Test
    void ratioRuleNeedsBothCountAndRate() {
        List<AlertEngine.Alert> sent = new ArrayList<>();
        var rule = new AlertRule("5xx", AlertRule.fieldEquals(Map.of("http.response.status_class", "5xx")),
                10, 0.05, Duration.ofMinutes(1), Duration.ofMinutes(10), "critical");
        var engine = new AlertEngine(List.of(rule), sent::add, clock);

        assertTrue(engine.observe(events(10, 990)).isEmpty(), "1% error rate is below the 5% ratio");
        now.set(now.get().plusSeconds(120));
        var fired = engine.observe(events(12, 100));
        assertEquals(1, fired.size());
        assertEquals(12, fired.get(0).count());
        assertTrue(fired.get(0).summary().contains("10.7% of 112"));
        assertEquals(sent, fired);
    }

    @Test
    void cooldownSuppressesRepeats() {
        var rule = new AlertRule("errors", AlertRule.fieldEquals(Map.of("http.response.status_class", "5xx")),
                1, null, Duration.ofMinutes(1), Duration.ofMinutes(5), "warning");
        var engine = new AlertEngine(List.of(rule), a -> {}, clock);
        assertEquals(1, engine.observe(events(1, 0)).size());
        now.set(now.get().plusSeconds(60));
        assertEquals(0, engine.observe(events(1, 0)).size(), "still cooling down");
        now.set(now.get().plusSeconds(300));
        assertEquals(1, engine.observe(events(1, 0)).size());
    }

    @Test
    void validatesRules() {
        assertThrows(IllegalArgumentException.class, () -> new AlertRule("x", e -> true, 0, null, Duration.ofMinutes(1), Duration.ZERO, "w"));
        assertThrows(IllegalArgumentException.class, () -> new AlertRule("x", e -> true, 1, 1.5, Duration.ofMinutes(1), Duration.ZERO, "w"));
    }
}
