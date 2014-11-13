package com.ericguerra.loglens.alert;

import com.ericguerra.loglens.model.LogEvent;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Fires when the number of matching events in {@code window} reaches {@code threshold}, optionally
 * as a ratio of all events (e.g. 5xx rate above 5%). {@code cooldown} suppresses repeat alerts.
 */
public record AlertRule(String name, Predicate<LogEvent> matches, long threshold, Double minRatio,
                        Duration window, Duration cooldown, String severity) {
    public AlertRule {
        Objects.requireNonNull(name);
        Objects.requireNonNull(matches);
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        if (minRatio != null && (minRatio <= 0 || minRatio > 1)) throw new IllegalArgumentException("minRatio must be in (0, 1]");
    }

    /** Builds a predicate from a simple field=value map, e.g. {"log.level": "error"} or {"http.response.status_class": "5xx"}. */
    public static Predicate<LogEvent> fieldEquals(Map<String, String> criteria) {
        return e -> criteria.entrySet().stream().allMatch(c -> c.getValue().equalsIgnoreCase(String.valueOf(e.get(c.getKey()))));
    }
}
