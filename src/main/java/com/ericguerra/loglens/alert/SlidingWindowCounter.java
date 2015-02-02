package com.ericguerra.loglens.alert;

import java.time.Duration;
import java.time.Instant;

/**
 * Counts events in a sliding time window using a ring of one-second buckets.
 * O(1) per event; memory is fixed at window/1s buckets regardless of event rate.
 */
public final class SlidingWindowCounter {
    private final int seconds;
    private final long[] counts;
    private final long[] bucketSecond;

    public SlidingWindowCounter(Duration window) {
        this.seconds = (int) Math.max(1, window.toSeconds());
        this.counts = new long[seconds];
        this.bucketSecond = new long[seconds];
    }

    public synchronized void add(Instant at, long n) {
        long s = at.getEpochSecond();
        int i = (int) Math.floorMod(s, seconds);
        if (bucketSecond[i] != s) {
            bucketSecond[i] = s;
            counts[i] = 0;
        }
        counts[i] += n;
    }

    /** Total for (now - window, now]. */
    public synchronized long total(Instant now) {
        long nowS = now.getEpochSecond();
        long sum = 0;
        for (int i = 0; i < seconds; i++) {
            if (nowS - bucketSecond[i] < seconds && bucketSecond[i] <= nowS) sum += counts[i];
        }
        return sum;
    }
}
