package com.ericguerra.loglens.parse;

import com.ericguerra.loglens.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LineParserTest {
    private final LineParser parser = new LineParser();
    private final Instant now = Instant.parse("2014-06-02T12:00:00Z");

    @Test
    void grokExpandsNestedPatternsAndTypes() {
        Grok g = Grok.compile("%{IP:ip} %{INT:n:int} %{QS:q}");
        Map<String, Object> m = g.match("10.0.0.1 42 \"hello \\\"world\\\"\"");
        assertEquals("10.0.0.1", m.get("ip"));
        assertEquals(42L, m.get("n"));
        assertEquals("hello \\\"world\\\"", m.get("q"));
        assertNull(g.match("not an ip 42 \"x\""));
        assertThrows(IllegalArgumentException.class, () -> Grok.compile("%{NOPE:x}"));
    }

    @Test
    void parsesNginxCombined() {
        LogEvent e = parser.parse("203.0.113.9 - alice [02/Jun/2014:10:15:32 -0500] \"GET /api/orders?id=7 HTTP/1.1\" 503 512 " +
                "\"https://shop.example.com/\" \"Mozilla/5.0 (Windows NT 6.1) Chrome/35.0\" 0.231", "nginx", now);
        assertEquals("nginx_combined", e.get("event.dataset"));
        assertEquals("203.0.113.9", e.get("client.ip"));
        assertEquals("alice", e.get("user.name"));
        assertEquals("GET", e.get("http.request.method"));
        assertEquals(503L, e.get("http.response.status_code"));
        assertEquals(0.231, e.get("event.duration_s"));
        assertEquals(Instant.parse("2014-06-02T15:15:32Z"), e.timestamp());
        assertNull(e.get("ident"), "'-' values are dropped");
    }

    @Test
    void parsesAppLogsWithThreadAndNormalizesLevel() {
        LogEvent e = parser.parse("2014-06-02 10:15:32,123 WARNING [pool-1-thread-3] com.acme.orders.PaymentService - Card declined for order 991", "app", now);
        assertEquals("app_log", e.get("event.dataset"));
        assertEquals("warn", e.get("log.level"));
        assertEquals("pool-1-thread-3", e.get("process.thread.name"));
        assertEquals("com.acme.orders.PaymentService", e.get("log.logger"));
        assertEquals("Card declined for order 991", e.get("log.message"));
        assertEquals(Instant.parse("2014-06-02T10:15:32.123Z"), e.timestamp());
    }

    @Test
    void parsesSyslogInferringYear() {
        LogEvent e = parser.parse("Jun  2 09:01:02 web-01 sshd[4242]: Failed password for root from 198.51.100.7", "syslog", now);
        assertEquals("syslog", e.get("event.dataset"));
        assertEquals("sshd", e.get("process.name"));
        assertEquals(4242L, e.get("process.pid"));
        assertEquals(Instant.parse("2014-06-02T09:01:02Z"), e.timestamp());

        // A December line received in early January belongs to the previous year.
        LogEvent dec = parser.parse("Dec 31 23:59:59 web-01 cron: done", "syslog", Instant.parse("2015-01-01T00:00:05Z"));
        assertEquals(Instant.parse("2014-12-31T23:59:59Z"), dec.timestamp());
    }

    @Test
    void parsesStructuredJson() {
        LogEvent e = parser.parse("{\"@timestamp\":\"2014-06-02T08:00:00Z\",\"level\":\"ERR\",\"msg\":\"boom\",\"user_id\":5}", "svc", now);
        assertEquals("json", e.get("event.dataset"));
        assertEquals("error", e.get("log.level"));
        assertEquals(5, e.get("user_id"));
        assertEquals(Instant.parse("2014-06-02T08:00:00Z"), e.timestamp());
    }

    @Test
    void unparseableLinesAreKeptAndTagged() {
        LogEvent e = parser.parse("something completely different", "x", now);
        assertTrue(e.tags().contains("_grokparsefailure"));
        assertEquals(now, e.timestamp());
        assertEquals("something completely different", e.toDocument().get("message"));
    }
}
