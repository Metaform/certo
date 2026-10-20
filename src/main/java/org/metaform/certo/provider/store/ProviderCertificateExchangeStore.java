package org.metaform.certo.provider.store;

import org.metaform.certo.provider.domain.ProviderCertificateExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * Store of the provider's {@code Certificate Exchange}es — a Spring Data JPA repository ({@code findById} /
 * {@code findAll} / {@code save} inherited). {@link JpaSpecificationExecutor} backs the request-backlog
 * queries (see {@code ExchangeSpecifications}).
 */
public interface ProviderCertificateExchangeStore extends
        JpaRepository<ProviderCertificateExchange, String>, JpaSpecificationExecutor<ProviderCertificateExchange> {

    /**
     * The exchange a counterparty (by verified DID) opened within a tenant with a given request
     * {@code messageId} — the SCE creation-idempotency lookup. At most one exists; the
     * {@code (participantContextId, counterpartyId, messageId)} unique constraint guarantees it, and it is
     * never released, so a retried request resolves to the same exchange even after a terminal outcome.
     */
    Optional<ProviderCertificateExchange> findByParticipantContextIdAndCounterpartyIdAndMessageId(
            String participantContextId, String counterpartyId, String messageId);
}
