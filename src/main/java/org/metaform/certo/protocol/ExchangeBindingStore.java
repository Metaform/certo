package org.metaform.certo.protocol;

import java.util.Optional;

/**
 * Store of {@link ExchangeBinding}s — which protocol version each exchange's counterparty speaks and how
 * to reach it. The port; {@code InMemoryExchangeBindingStore} is the default (in-memory) adapter,
 * selectable via {@code certo.persistence}.
 */
public interface ExchangeBindingStore {

    void record(ExchangeBinding binding);

    Optional<ExchangeBinding> byExchangeId(String exchangeId);

    Optional<ExchangeBinding> byCertificateId(String certificateId);

    /**
     * Resolves the binding for an exchange whose counterparty plays the given role, by {@code exchangeId}
     * then {@code certificateId}. A binding for the other role is ignored.
     */
    Optional<ExchangeBinding> resolve(String exchangeId, String certificateId, CounterpartyRole role);

    /** Resolves the v3 {@code exchangeId} for a {@code certificateId} reported by the given peer. */
    Optional<String> exchangeFor(String certificateId, String peerBpn);
}
