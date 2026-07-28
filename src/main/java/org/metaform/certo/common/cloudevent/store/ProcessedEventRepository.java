package org.metaform.certo.common.cloudevent.store;

import org.metaform.certo.common.cloudevent.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ProcessedEvent} idempotency markers. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
