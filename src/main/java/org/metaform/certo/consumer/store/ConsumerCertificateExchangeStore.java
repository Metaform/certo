package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Store of the consumer's {@code Certificate Exchange}es — a Spring Data JPA repository ({@code findById} /
 * {@code findAll} / {@code save} inherited). The reconciliation queries are scoped to a tenant and bounded in
 * the database (rather than scanning every tenant's exchanges into memory).
 */
public interface ConsumerCertificateExchangeStore extends JpaRepository<ConsumerCertificateExchange, String> {

    /** A tenant's exchanges (bounded/ordered by the {@link Pageable}). */
    List<ConsumerCertificateExchange> findByParticipantContextId(String participantContextId, Pageable pageable);

    /**
     * The tenant's exchanges awaiting the caller's action: {@code FULFILLED} but not yet accepted (outstanding
     * retrieve/accept), or accepted but whose report was not confirmed delivered (needs re-reporting). The
     * safety net for a dropped notification callback or a lost acceptance report.
     */
    @Query("""
            select e from ConsumerCertificateExchange e
            where e.participantContextId = :pctx
              and ((e.fulfillmentStatus = org.metaform.certo.common.model.FulfillmentStatus.FULFILLED
                        and e.acceptanceStatus is null)
                   or (e.acceptanceStatus is not null and e.acceptanceReported = false))
            """)
    List<ConsumerCertificateExchange> findAwaitingAction(@Param("pctx") String participantContextId, Pageable pageable);
}
