package com.ericguerra.loglens.broker;

import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis list consumer (the classic Logstash "broker" pattern). BLPOP for the first element so idle
 * workers sleep server-side, then LPOP with a count to drain a batch in one round trip.
 */
public final class RedisLogQueue implements LogQueue {
    private final JedisPooled redis;
    private final String key;

    public RedisLogQueue(String uri, String key) {
        this.redis = new JedisPooled(java.net.URI.create(uri));
        this.key = key;
    }

    @Override
    public List<String> take(int max, Duration wait) {
        List<String> first = redis.blpop((int) Math.max(1, wait.toSeconds()), key);
        if (first == null || first.size() < 2) return List.of();
        List<String> batch = new ArrayList<>(max);
        batch.add(first.get(1));
        if (max > 1) {
            List<String> more = redis.lpop(key, max - 1);
            if (more != null) batch.addAll(more);
        }
        return batch;
    }

    @Override
    public void push(String list, List<String> messages) {
        if (!messages.isEmpty()) redis.rpush(list, messages.toArray(String[]::new));
    }

    public long depth() {
        return redis.llen(key);
    }

    @Override
    public void close() {
        redis.close();
    }
}
