package com.ericguerra.loglens.broker;

import java.time.Duration;
import java.util.List;

/**
 * Source of raw log messages. Logstash shippers push JSON envelopes ({"message", "source"}) onto a
 * Redis list; workers pop in batches.
 */
public interface LogQueue extends AutoCloseable {
    /** Blocks up to {@code wait} for the first message, then drains up to {@code max} without blocking. */
    List<String> take(int max, Duration wait) throws InterruptedException;

    /** Pushes messages back (e.g. to a dead-letter list). */
    void push(String list, List<String> messages);

    @Override
    default void close() {}
}
