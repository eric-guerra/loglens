package com.ericguerra.loglens.sink;

import com.ericguerra.loglens.model.LogEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a fake _bulk endpoint so retry and partial-failure handling can be scripted. */
class ElasticsearchBulkSinkTest {
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile Function<Integer, String[]> responder; // call number -> {status, body}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_bulk", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String[] r = responder.apply(bodies.size());
            byte[] out = r[1].getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(Integer.parseInt(r[0]), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ElasticsearchBulkSink sink(int retries) {
        return new ElasticsearchBulkSink("http://127.0.0.1:" + server.getAddress().getPort(), "logs", null, null, retries, 1);
    }

    private static List<LogEvent> events(int n) {
        List<LogEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new LogEvent("line " + i, "t", Instant.parse("2014-06-02T23:59:59Z")).put("n", i));
        return out;
    }

    @Test
    void writesNdjsonToDailyIndex() throws Exception {
        responder = call -> new String[]{"200", "{\"errors\":false,\"items\":[]}"};
        var result = sink(3).write(events(2));
        assertEquals(2, result.written());
        assertTrue(result.failed().isEmpty());
        String[] lines = bodies.get(0).split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"_index\":\"logs-2014.06.02\""));
        assertTrue(lines[1].contains("\"message\":\"line 0\""));
        assertTrue(lines[1].contains("\"@timestamp\":\"2014-06-02T23:59:59Z\""));
    }

    @Test
    void retriesOnlyRetryableItems() throws Exception {
        responder = call -> call == 1
                ? new String[]{"200", "{\"errors\":true,\"items\":[" +
                    "{\"index\":{\"status\":201}}," +
                    "{\"index\":{\"status\":429,\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}}," +
                    "{\"index\":{\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad field\"}}}]}"}
                : new String[]{"200", "{\"errors\":false,\"items\":[{\"index\":{\"status\":201}}]}"};
        var result = sink(3).write(events(3));
        assertEquals(2, result.written());
        assertEquals(1, result.failed().size());
        assertTrue(result.failed().get(0).reason().startsWith("mapper_parsing_exception"));
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(1).contains("line 1") && !bodies.get(1).contains("line 0"), "only the rejected item is resent");
    }

    @Test
    void givesUpAfterMaxRetries() throws Exception {
        responder = call -> new String[]{"503", "unavailable"};
        var result = sink(2).write(events(2));
        assertEquals(0, result.written());
        assertEquals(2, result.failed().size());
        assertEquals(3, bodies.size(), "initial attempt + 2 retries");
    }

    @Test
    void permanentRequestErrorsAreNotRetried() throws Exception {
        responder = call -> new String[]{"400", "{\"error\":\"bad request\"}"};
        var result = sink(5).write(events(1));
        assertEquals(1, result.failed().size());
        assertEquals(1, bodies.size());
    }
}
