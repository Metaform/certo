package org.metaform.certo.common.cloudevent;

/**
 * Idempotency store for processed CloudEvents (keyed by {@code source + id}). The port;
 * {@code InMemoryProcessedEventStore} is the default (in-memory) adapter, selectable via
 * {@code certo.persistence}.
 */
public interface ProcessedEventStore {

    /** Records the event key and returns {@code true} the first time it is seen, {@code false} thereafter. */
    boolean firstSeen(String eventKey);
}
