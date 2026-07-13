package org.metaform.certo.common.cloudevent.memory;

import org.metaform.certo.common.cloudevent.ProcessedEventStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which CloudEvents have already been processed so duplicate deliveries (retries) are ignored.
 * CX-0000 &sect;2.2: {@code source} + {@code id} uniquely identifies an event, and retries reuse them.
 *
 * <p>Each key is stored with the wall-clock instant it was first seen (from an injected {@link Clock},
 * so it stays testable). The timestamps are not consulted today, but they let a future reaper thread
 * evict entries older than some retention window to bound this otherwise unbounded in-memory store.
 */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Map<String, Instant> processed = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryProcessedEventStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * Atomically records that an event key has been seen, stamping it with the current time on first
     * sight.
     *
     * @return {@code true} if this is the first time the key is seen (process it), {@code false} if it
     *         is a duplicate (skip it)
     */
    public boolean firstSeen(String eventKey) {
        return processed.putIfAbsent(eventKey, clock.instant()) == null;
    }
}
