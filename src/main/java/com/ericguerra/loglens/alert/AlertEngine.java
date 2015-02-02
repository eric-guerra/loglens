package com.ericguerra.loglens.alert;

import com.ericguerra.loglens.model.LogEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Evaluates alert rules against the event stream and hands fired alerts to a notifier. */
public final class AlertEngine {
    public record Alert(String rule, String severity, Instant at, long count, long total, String sample) {
        public String summary() {
            String ratio = total > 0 ? String.format(" (%.1f%% of %d)", 100.0 * count / total, total) : "";
            return "[" + severity.toUpperCase() + "] " + rule + ": " + count + " events" + ratio + "; e.g. " + sample;
        }
    }

    private record State(SlidingWindowCounter matched, SlidingWindowCounter all) {}

    private final List<AlertRule> rules;
    private final Map<String, State> state = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final Consumer<Alert> notifier;
    private final Clock clock;

    public AlertEngine(List<AlertRule> rules, Consumer<Alert> notifier, Clock clock) {
        this.rules = List.copyOf(rules);
        this.notifier = notifier;
        this.clock = clock;
        for (AlertRule r : rules) state.put(r.name(), new State(new SlidingWindowCounter(r.window()), new SlidingWindowCounter(r.window())));
    }

    /** Uses processing time, not event time, so delayed backfills don't trigger stale alerts. */
    public List<Alert> observe(List<LogEvent> batch) {
        Instant now = clock.instant();
        List<Alert> fired = new ArrayList<>();
        for (AlertRule rule : rules) {
            State s = state.get(rule.name());
            String sample = null;
            long matched = 0;
            for (LogEvent e : batch) {
                if (rule.matches().test(e)) {
                    matched++;
                    if (sample == null) sample = e.raw().length() > 200 ? e.raw().substring(0, 200) : e.raw();
                }
            }
            s.all().add(now, batch.size());
            if (matched > 0) s.matched().add(now, matched);

            long count = s.matched().total(now);
            long total = s.all().total(now);
            boolean over = count >= rule.threshold() && (rule.minRatio() == null || (total > 0 && (double) count / total >= rule.minRatio()));
            if (!over || sample == null) continue;

            Instant last = lastFired.get(rule.name());
            if (last != null && last.plus(rule.cooldown()).isAfter(now)) continue;
            lastFired.put(rule.name(), now);
            Alert alert = new Alert(rule.name(), rule.severity(), now, count, rule.minRatio() == null ? 0 : total, sample);
            fired.add(alert);
            notifier.accept(alert);
        }
        return fired;
    }
}
