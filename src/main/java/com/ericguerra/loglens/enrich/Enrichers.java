package com.ericguerra.loglens.enrich;

import com.ericguerra.loglens.model.LogEvent;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Built-in enrichers applied after parsing. */
public final class Enrichers {
    private Enrichers() {}

    public static List<Enricher> defaults() {
        return List.of(Enrichers::httpOutcome, Enrichers::urlParts, Enrichers::userAgent, Enrichers::privateNetwork, Enrichers::redactSecrets);
    }

    /** 2xx/3xx success, 4xx client error, 5xx server error, plus a numeric class for aggregations. */
    static void httpOutcome(LogEvent e) {
        Integer status = e.getInt("http.response.status_code");
        if (status == null) return;
        e.put("http.response.status_class", (status / 100) + "xx");
        e.put("event.outcome", status >= 500 ? "failure" : status >= 400 ? "client_error" : "success");
        if (status >= 500) e.put("log.level", "error");
        else if (status >= 400 && e.get("log.level") == null) e.put("log.level", "warn");
        else if (e.get("log.level") == null) e.put("log.level", "info");
    }

    static void urlParts(LogEvent e) {
        String original = e.getString("url.original");
        if (original == null) return;
        try {
            URI uri = URI.create(original.startsWith("/") ? "http://x" + original : original);
            e.put("url.path", uri.getRawPath());
            if (uri.getRawQuery() != null) e.put("url.query", uri.getRawQuery());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            int dot = path.lastIndexOf('.');
            if (dot > path.lastIndexOf('/')) e.put("url.extension", path.substring(dot + 1).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            e.tag("_urlparsefailure");
        }
    }

    private static final Pattern BROWSER = Pattern.compile("(Edg|OPR|Chrome|Firefox|Safari|MSIE|Trident)/?\\s*([\\d.]+)?");
    private static final Pattern BOT = Pattern.compile("(?i)bot|crawler|spider|slurp|curl|wget|python-requests|httpclient");

    /** Lightweight user-agent classification (browser family, OS family, bot flag). */
    static void userAgent(LogEvent e) {
        String ua = e.getString("user_agent.original");
        if (ua == null) return;
        if (BOT.matcher(ua).find()) {
            e.put("user_agent.name", "bot");
            e.tag("bot");
            return;
        }
        Matcher m = BROWSER.matcher(ua);
        String name = null;
        // Order matters: Edge and Opera also claim Chrome, Chrome also claims Safari.
        for (String family : List.of("Edg", "OPR", "Firefox", "Chrome", "Safari", "MSIE", "Trident")) {
            if (ua.contains(family + "/") || (family.equals("MSIE") && ua.contains("MSIE "))) {
                name = switch (family) {
                    case "Edg" -> "Edge";
                    case "OPR" -> "Opera";
                    case "MSIE", "Trident" -> "IE";
                    default -> family;
                };
                break;
            }
        }
        if (name == null && m.find()) name = m.group(1);
        if (name != null) e.put("user_agent.name", name);
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS" : ua.contains("Mac OS X") ? "macOS"
                : ua.contains("Linux") ? "Linux" : null;
        if (os != null) e.put("user_agent.os.name", os);
    }

    static void privateNetwork(LogEvent e) {
        String ip = e.getString("client.ip");
        if (ip == null) return;
        boolean internal = ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.equals("::1")
                || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
        e.put("network.internal", internal);
    }

    private static final Pattern SECRET = Pattern.compile("(?i)((?:password|passwd|token|api[_-]?key|secret|authorization)=)[^&\\s]+");

    /** Masks credentials that leak into query strings or messages before they reach the index. */
    static void redactSecrets(LogEvent e) {
        for (String key : List.of("url.original", "url.query", "log.message")) {
            String v = e.getString(key);
            if (v != null && SECRET.matcher(v).find()) {
                e.put(key, SECRET.matcher(v).replaceAll("$1[REDACTED]"));
                e.tag("redacted");
            }
        }
    }
}
