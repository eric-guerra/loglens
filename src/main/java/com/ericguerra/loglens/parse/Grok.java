package com.ericguerra.loglens.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Grok: expands %{NAME} and %{NAME:field} (optionally %{NAME:field:int}) into named regex
 * groups using a library of base patterns, the same syntax Logstash uses.
 */
public final class Grok {
    private static final Pattern REF = Pattern.compile("%\\{(\\w+)(?::([\\w.@]+))?(?::(int|float))?}");

    public static final Map<String, String> BASE = Map.ofEntries(
            Map.entry("INT", "[+-]?\\d+"),
            Map.entry("NUMBER", "[+-]?\\d+(?:\\.\\d+)?"),
            Map.entry("WORD", "\\w+"),
            Map.entry("NOTSPACE", "\\S+"),
            Map.entry("DATA", ".*?"),
            Map.entry("GREEDYDATA", ".*"),
            Map.entry("QS", "\"(?:[^\"\\\\]|\\\\.)*\""),
            Map.entry("IPV4", "(?:\\d{1,3}\\.){3}\\d{1,3}"),
            Map.entry("IPV6", "[0-9A-Fa-f:]+:[0-9A-Fa-f:.]+"),
            Map.entry("IP", "%{IPV6}|%{IPV4}"),
            Map.entry("HOSTNAME", "[A-Za-z0-9][A-Za-z0-9.-]*"),
            Map.entry("IPORHOST", "%{IP}|%{HOSTNAME}"),
            Map.entry("USER", "[A-Za-z0-9._@-]+|-"),
            Map.entry("HTTPDATE", "\\d{2}/\\w{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
            Map.entry("TIMESTAMP_ISO8601", "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"),
            Map.entry("SYSLOGTIMESTAMP", "\\w{3} +\\d{1,2} \\d{2}:\\d{2}:\\d{2}"),
            Map.entry("LOGLEVEL", "TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|ERR|CRIT(?:ICAL)?|FATAL|SEVERE|ALERT|EMERG"),
            Map.entry("JAVACLASS", "(?:[a-zA-Z$_][a-zA-Z$_0-9]*\\.)*[a-zA-Z$_][a-zA-Z$_0-9]*"),
            Map.entry("PROG", "[\\w._/%-]+"),
            Map.entry("UUID", "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    );

    public record Capture(String group, String field, String type) {}

    private final Pattern pattern;
    private final List<Capture> captures;

    private Grok(Pattern pattern, List<Capture> captures) {
        this.pattern = pattern;
        this.captures = captures;
    }

    public static Grok compile(String expression) {
        List<Capture> captures = new ArrayList<>();
        String regex = expand(expression, captures, 0);
        return new Grok(Pattern.compile("^" + regex + "$"), captures);
    }

    private static String expand(String expr, List<Capture> captures, int depth) {
        if (depth > 10) throw new IllegalArgumentException("grok pattern recursion too deep");
        Matcher m = REF.matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String base = BASE.get(name);
            if (base == null) throw new IllegalArgumentException("unknown grok pattern %{" + name + "}");
            String inner = "(?:" + expand(base, null, depth + 1) + ")";
            String replacement;
            if (m.group(2) != null && captures != null) {
                String group = "g" + captures.size();
                captures.add(new Capture(group, m.group(2), m.group(3)));
                replacement = "(?<" + group + ">" + inner + ")";
            } else {
                replacement = inner;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Returns extracted fields, or null if the line doesn't match. Fields equal to "-" are dropped. */
    public Map<String, Object> match(String line) {
        Matcher m = pattern.matcher(line);
        if (!m.matches()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Capture c : captures) {
            String v = m.group(c.group());
            if (v == null) continue;
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            if (v.equals("-") || v.isEmpty()) continue;
            try {
                out.put(c.field(), "int".equals(c.type()) ? (Object) Long.parseLong(v)
                        : "float".equals(c.type()) ? (Object) Double.parseDouble(v) : v);
            } catch (NumberFormatException e) {
                out.put(c.field(), v);
            }
        }
        return out;
    }
}
