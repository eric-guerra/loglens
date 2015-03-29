package com.ericguerra.loglens.enrich;

import com.ericguerra.loglens.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EnrichersTest {
    private static LogEvent event() {
        return new LogEvent("raw", "test", Instant.EPOCH);
    }

    @Test
    void httpOutcomeAndLevel() {
        LogEvent e = event().put("http.response.status_code", 502L);
        Enrichers.httpOutcome(e);
        assertEquals("5xx", e.get("http.response.status_class"));
        assertEquals("failure", e.get("event.outcome"));
        assertEquals("error", e.get("log.level"));

        LogEvent ok = event().put("http.response.status_code", 200L);
        Enrichers.httpOutcome(ok);
        assertEquals("info", ok.get("log.level"));
    }

    @Test
    void urlPartsAndRedaction() {
        LogEvent e = event().put("url.original", "/login.php?user=bob&password=hunter2&next=/");
        Enrichers.urlParts(e);
        Enrichers.redactSecrets(e);
        assertEquals("/login.php", e.get("url.path"));
        assertEquals("php", e.get("url.extension"));
        assertEquals("/login.php?user=bob&password=[REDACTED]&next=/", e.get("url.original"));
        assertEquals("user=bob&password=[REDACTED]&next=/", e.get("url.query"));
        assertTrue(e.tags().contains("redacted"));
    }

    @Test
    void userAgentFamilies() {
        assertUa("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0", "Edge", "Windows");
        assertUa("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9) AppleWebKit/537.36 Chrome/35.0 Safari/537.36", "Chrome", "macOS");
        assertUa("Mozilla/5.0 (iPhone; CPU iPhone OS 7_0 like Mac OS X) AppleWebKit Version/7.0 Safari/9537", "Safari", "iOS");
        assertUa("Mozilla/5.0 (X11; Linux x86_64; rv:30.0) Gecko/20100101 Firefox/30.0", "Firefox", "Linux");

        LogEvent bot = event().put("user_agent.original", "Googlebot/2.1 (+http://www.google.com/bot.html)");
        Enrichers.userAgent(bot);
        assertEquals("bot", bot.get("user_agent.name"));
        assertTrue(bot.tags().contains("bot"));
    }

    private static void assertUa(String ua, String name, String os) {
        LogEvent e = event().put("user_agent.original", ua);
        Enrichers.userAgent(e);
        assertEquals(name, e.get("user_agent.name"), ua);
        assertEquals(os, e.get("user_agent.os.name"), ua);
    }

    @Test
    void privateNetworks() {
        for (String ip : new String[]{"10.1.2.3", "192.168.0.10", "172.20.1.1", "127.0.0.1"}) {
            LogEvent e = event().put("client.ip", ip);
            Enrichers.privateNetwork(e);
            assertEquals(true, e.get("network.internal"), ip);
        }
        LogEvent pub = event().put("client.ip", "172.32.0.1");
        Enrichers.privateNetwork(pub);
        assertEquals(false, pub.get("network.internal"));
    }
}
