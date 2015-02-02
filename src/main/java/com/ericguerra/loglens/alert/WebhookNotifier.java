package com.ericguerra.loglens.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/** Posts alerts as Slack-compatible JSON ({"text": ...}); failures are logged, never thrown into the pipeline. */
public final class WebhookNotifier implements Consumer<AlertEngine.Alert> {
    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI url;

    public WebhookNotifier(String url) {
        this.url = URI.create(url);
    }

    @Override
    public void accept(AlertEngine.Alert alert) {
        try {
            String body = new ObjectMapper().writeValueAsString(Map.of("text", alert.summary()));
            http.sendAsync(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((r, err) -> {
                        if (err != null) log.warn("alert webhook failed: {}", err.toString());
                        else if (r.statusCode() >= 300) log.warn("alert webhook returned {}", r.statusCode());
                    });
        } catch (Exception e) {
            log.warn("could not send alert: {}", e.toString());
        }
    }
}
