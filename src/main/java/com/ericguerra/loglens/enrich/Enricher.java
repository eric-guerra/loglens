package com.ericguerra.loglens.enrich;

import com.ericguerra.loglens.model.LogEvent;

@FunctionalInterface
public interface Enricher {
    void enrich(LogEvent event);
}
