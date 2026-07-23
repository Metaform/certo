package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.ProviderCertificateExchange;
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
     * The single still-live exchange a counterparty (by verified DID) has opened within a tenant for a given
     * (namespaced) dedup key, reused for a repeat rather than opening a duplicate (CX-0135 &sect;2.1.1). At
     * most one exists — the {@code (participantContextId, counterpartyDid, liveDedupKey)} unique constraint
     * guarantees it — and a terminal exchange (key nulled) is not returned. Used by both the consumer-open
     * ({@code "req:"} keys) and provider-publish ({@code "pub:"} keys) paths.
     */
    Optional<ProviderCertificateExchange> findByParticipantContextIdAndCounterpartyDidAndLiveDedupKey(
            String participantContextId, String counterpartyDid, String liveDedupKey);
}
