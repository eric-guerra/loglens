package com.ericguerra.loglens.broker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test and local-dev queue. */
public final class InMemoryLogQueue implements LogQueue {
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Map<String, List<String>> lists = new ConcurrentHashMap<>();

    public void offer(String message) {
        queue.add(message);
    }

    @Override
    public List<String> take(int max, Duration wait) throws InterruptedException {
        String first = queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (first == null) return List.of();
        List<String> batch = new ArrayList<>(max);
        batch.add(first);
        queue.drainTo(batch, max - 1);
        return batch;
    }

    @Override
    public void push(String list, List<String> messages) {
        lists.computeIfAbsent(list, k -> new CopyOnWriteArrayList<>()).addAll(messages);
    }

    public List<String> list(String name) {
        return lists.getOrDefault(name, List.of());
    }

    public int size() {
        return queue.size();
    }
}
