package org.metaform.certo.common.cloudevent;

import org.metaform.certo.common.cloudevent.store.ProcessedEventStore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link EventBatch#applyDeduplicated}: apply each newly-claimed event once; skip duplicates. */
class EventBatchTest {

    /** An in-memory {@link ProcessedEventStore}: claim succeeds the first time a key is seen. */
    private static ProcessedEventStore inMemoryStore() {
        Set<String> claimed = new HashSet<>();
        return claimed::add;   // Set.add returns true when newly added == newly claimed
    }

    @Test
    void appliesEachDistinctEventOnce() {
        var applied = new ArrayList<String>();
        var events = List.of(
                new EventBatch.PendingEvent("k1", () -> applied.add("a")),
                new EventBatch.PendingEvent("k2", () -> applied.add("b")));

        EventBatch.applyDeduplicated(events, inMemoryStore());

        assertThat(applied).containsExactly("a", "b");
    }

    @Test
    void skipsAnEventWhoseKeyWasAlreadyClaimed() {
        var applied = new ArrayList<String>();
        var events = List.of(
                new EventBatch.PendingEvent("dup", () -> applied.add("first")),
                new EventBatch.PendingEvent("dup", () -> applied.add("second")));   // same key

        EventBatch.applyDeduplicated(events, inMemoryStore());

        assertThat(applied).containsExactly("first");   // the duplicate is skipped
    }

    @Test
    void skipsAnEventAlreadyProcessedInAnEarlierBatch() {
        var store = inMemoryStore();
        var applied = new ArrayList<String>();
        EventBatch.applyDeduplicated(List.of(new EventBatch.PendingEvent("k", () -> applied.add("one"))), store);
        EventBatch.applyDeduplicated(List.of(new EventBatch.PendingEvent("k", () -> applied.add("two"))), store);

        assertThat(applied).containsExactly("one");
    }
}
