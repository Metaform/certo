package org.metaform.certo.common.cloudevent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A processed-CloudEvent idempotency marker (keyed by {@code source + id}). One row per handled event; its
 * presence means "already applied". Written inside the same transaction as the event's side effect, so a
 * rollback removes it and a retry re-applies — which is why the former two-phase claim/release compensation
 * is no longer needed.
 *
 * <p>The {@code firstSeen} timestamp is retained (not read today) so a future reaper can evict markers
 * older than a retention window and bound the table.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_key")
    private String eventKey;
    private Instant firstSeen;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(String eventKey, Instant firstSeen) {
        this.eventKey = eventKey;
        this.firstSeen = firstSeen;
    }

    public String eventKey() {
        return eventKey;
    }

    public Instant firstSeen() {
        return firstSeen;
    }
}
