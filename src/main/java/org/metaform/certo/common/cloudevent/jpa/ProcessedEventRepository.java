package org.metaform.certo.common.cloudevent.jpa;

import org.metaform.certo.common.cloudevent.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ProcessedEvent} idempotency markers. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
