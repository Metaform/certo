package org.metaform.certo.common.cloudevent.jpa;

import org.metaform.certo.common.cloudevent.ProcessedEvent;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * JPA-backed idempotency store. {@code claim} runs inside the caller's transaction: it checks for an
 * existing marker (which auto-flushes any pending insert in the same transaction, so an intra-batch
 * duplicate is caught) and inserts one if absent. The {@code event_key} primary key is the ultimate
 * backstop — two concurrent transactions that both insert the same key collide, one rolls back, and its
 * retry then sees the committed marker and skips. A rollback of the surrounding transaction removes the
 * marker, so a failed apply is re-applied on retry.
 */
@Component
public class JpaProcessedEventStore implements ProcessedEventStore {

    private final ProcessedEventRepository repository;
    private final Clock clock;

    public JpaProcessedEventStore(ProcessedEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public boolean claim(String eventKey) {
        if (repository.existsById(eventKey)) {
            return false;
        }
        repository.save(new ProcessedEvent(eventKey, clock.instant()));
        return true;
    }
}
