package org.metaform.certo.common.cloudevent;

import java.util.List;

/**
 * Applies a validated batch of inbound CloudEvents idempotently: each event is claimed via the
 * {@link ProcessedEventStore} and applied only if newly claimed (a duplicate is skipped). Intended to run
 * inside the caller's transaction, so a failure rolls the whole batch back — claims included — and a retry
 * re-applies rather than silently skipping. Shared by the provider (acceptance) and consumer (notification)
 * inbound paths.
 */
public final class EventBatch {

    private EventBatch() {
    }

    /** A validated inbound event: its dedup key ({@code source + id}) and the side effect to apply once. */
    public record PendingEvent(String dedupKey, Runnable apply) {
    }

    public static void applyDeduplicated(List<PendingEvent> events, ProcessedEventStore store) {
        for (var event : events) {
            if (store.claim(event.dedupKey())) {
                event.apply().run();
            }
        }
    }
}
