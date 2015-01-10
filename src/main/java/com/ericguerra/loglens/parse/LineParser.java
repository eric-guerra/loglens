package com.ericguerra.loglens.parse;

import com.ericguerra.loglens.model.LogEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tries structured JSON first, then a list of grok patterns in order. Unparseable lines are
 * kept (tagged `_grokparsefailure`) so nothing is silently dropped.
 */
public final class LineParser {
    public record Format(String name, Grok grok, String timestampField, DateTimeFormatter timestampFormat) {}

    private static final DateTimeFormatter HTTPDATE = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter SYSLOG = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final List<Format> DEFAULT_FORMATS = List.of(
            new Format("nginx_combined", Grok.compile(
                    "%{IPORHOST:client.ip} %{USER:ident} %{USER:user.name} \\[%{HTTPDATE:ts}\\] " +
                    "\"%{WORD:http.request.method} %{NOTSPACE:url.original} HTTP/%{NUMBER:http.version}\" " +
                    "%{INT:http.response.status_code:int} (?:%{INT:http.response.body.bytes:int}|-) " +
                    "%{QS:http.request.referrer} %{QS:user_agent.original}(?: %{NUMBER:event.duration_s:float})?"),
                    "ts", HTTPDATE),
            new Format("app_log", Grok.compile(
                    "%{TIMESTAMP_ISO8601:ts} +%{LOGLEVEL:log.level} +(?:\\[%{DATA:process.thread.name}\\] +)?" +
                    "%{JAVACLASS:log.logger} *[-:] +%{GREEDYDATA:log.message}"),
                    "ts", null),
            new Format("syslog", Grok.compile(
                    "%{SYSLOGTIMESTAMP:ts} %{HOSTNAME:host.name} %{PROG:process.name}(?:\\[%{INT:process.pid:int}\\])?: %{GREEDYDATA:log.message}"),
                    "ts", SYSLOG)
    );

    private final List<Format> formats;

    public LineParser() {
        this(DEFAULT_FORMATS);
    }

    public LineParser(List<Format> formats) {
        this.formats = formats;
    }

    public LogEvent parse(String line, String source, Instant receivedAt) {
        LogEvent event = new LogEvent(line, source, receivedAt);
        String trimmed = line.strip();

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> obj = JSON.readValue(trimmed, new TypeReference<>() {});
                Object ts = obj.remove("@timestamp");
                if (ts == null) ts = obj.remove("timestamp");
                if (ts == null) ts = obj.remove("time");
                event.timestamp(parseIso(ts == null ? null : ts.toString()));
                normalizeLevel(obj);
                obj.forEach(event::put);
                event.put("event.dataset", "json");
                return event;
            } catch (Exception ignored) {
                // fall through to grok
            }
        }

        for (Format f : formats) {
            Map<String, Object> fields = f.grok().match(trimmed);
            if (fields == null) continue;
            Object ts = fields.remove(f.timestampField());
            if (ts != null) event.timestamp(parseTimestamp(ts.toString(), f.timestampFormat(), receivedAt));
            normalizeLevel(fields);
            fields.forEach(event::put);
            event.put("event.dataset", f.name());
            return event;
        }
        event.tag("_grokparsefailure");
        return event;
    }

    private static void normalizeLevel(Map<String, Object> fields) {
        Object level = fields.containsKey("log.level") ? fields.get("log.level") : fields.remove("level");
        if (level == null) return;
        String l = level.toString().toLowerCase(Locale.ROOT);
        l = switch (l) {
            case "warning" -> "warn";
            case "err", "severe" -> "error";
            case "critical", "crit", "fatal", "alert", "emerg" -> "fatal";
            default -> l;
        };
        fields.put("log.level", l);
    }

    static Instant parseTimestamp(String value, DateTimeFormatter format, Instant receivedAt) {
        try {
            if (format == HTTPDATE) return ZonedDateTime.parse(value, HTTPDATE).toInstant();
            if (format == SYSLOG) {
                // Syslog omits the year: assume the receive year, rolling back if that lands in the future.
                int year = receivedAt.atZone(ZoneOffset.UTC).getYear();
                String normalized = value.replaceAll(" +", " ");
                Instant t = LocalDateTime.parse(normalized + " " + year, SYSLOG).toInstant(ZoneOffset.UTC);
                if (t.isAfter(receivedAt.plusSeconds(86400))) t = LocalDateTime.parse(normalized + " " + Year.of(year - 1), SYSLOG).toInstant(ZoneOffset.UTC);
                return t;
            }
            return parseIso(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Instant parseIso(String value) {
        if (value == null) return null;
        String v = value.replace(' ', 'T').replace(',', '.');
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(v.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
            } catch (DateTimeParseException e2) {
                try {
                    return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException e3) {
                    return null;
                }
            }
        }
    }
}
