package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Store of the provider's {@code Certificate Exchange}es — a Spring Data JPA repository ({@code findById} /
 * {@code findAll} / {@code save} inherited). {@link JpaSpecificationExecutor} backs the request-backlog
 * queries (see {@code ExchangeSpecifications}).
 */
public interface ProviderCertificateExchangeStore extends
        JpaRepository<ProviderCertificateExchange, String>, JpaSpecificationExecutor<ProviderCertificateExchange> {

    /**
     * The exchanges a counterparty has opened within a tenant for a given canonical request key (an indexed
     * lookup, at most a handful of rows). {@code requestCertificate} filters these to a live one to reuse for
     * a repeated request (CX-0135 &sect;2.1.1).
     */
    List<ProviderCertificateExchange> findByParticipantContextIdAndCounterpartyBpnAndRequestKey(
            String participantContextId, String counterpartyBpn, String requestKey);
}
