package com.ericguerra.loglens.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed log line. `fields` holds everything extracted or enriched; well-known
 * keys use ECS-style names (http.response.status_code, client.ip, ...).
 */
public final class LogEvent {
    private final String raw;
    private final String source;
    private Instant timestamp;
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private final java.util.List<String> tags = new java.util.ArrayList<>();

    public LogEvent(String raw, String source, Instant receivedAt) {
        this.raw = Objects.requireNonNull(raw);
        this.source = source == null ? "unknown" : source;
        this.timestamp = receivedAt;
    }

    public String raw() { return raw; }
    public String source() { return source; }
    public Instant timestamp() { return timestamp; }
    public void timestamp(Instant t) { if (t != null) this.timestamp = t; }
    public Map<String, Object> fields() { return fields; }
    public java.util.List<String> tags() { return tags; }

    public LogEvent put(String key, Object value) {
        if (value != null) fields.put(key, value);
        return this;
    }

    public Object get(String key) {
        return fields.get(key);
    }

    public String getString(String key) {
        Object v = fields.get(key);
        return v == null ? null : v.toString();
    }

    public Integer getInt(String key) {
        Object v = fields.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public void tag(String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }

    /** Document body for Elasticsearch. */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", timestamp.toString());
        doc.put("message", raw);
        doc.put("log.source", source);
        doc.putAll(fields);
        if (!tags.isEmpty()) doc.put("tags", tags);
        return doc;
    }
}
