package org.metaform.certo.common.cloudevent;

/**
 * Idempotency store for processed CloudEvents (keyed by {@code source + id}). The port; the JPA adapter is
 * the sole implementation.
 *
 * <p>{@link #claim} is called inside the same transaction as the event's side effect. If the side effect
 * (or a later event in the batch) throws, the transaction rolls back and the claim is undone, so a retry
 * re-applies — no compensating "release" is needed. A committed claim is the durable "processed" record.
 */
public interface ProcessedEventStore {

    /**
     * Atomically claims an event key for processing. Returns {@code true} if this caller newly claimed it
     * (proceed with the side effect); {@code false} if it was already claimed/processed (skip — a duplicate).
     * Must be called within the transaction that also performs the side effect.
     */
    boolean claim(String eventKey);
}
