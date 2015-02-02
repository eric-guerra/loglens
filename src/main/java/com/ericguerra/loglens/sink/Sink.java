package com.ericguerra.loglens.sink;

import com.ericguerra.loglens.model.LogEvent;

import java.util.List;

public interface Sink {
    /** Result of a write: events that could not be stored even after retries. */
    record Result(int written, List<Failed> failed) {}

    record Failed(LogEvent event, String reason) {}

    Result write(List<LogEvent> events) throws InterruptedException;
}
