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

    /**
     * Resolves the binding for an exchange whose counterparty plays the given role, by {@code exchangeId}.
     * A binding for the other role is ignored (an exchange has one counterparty per direction).
     */
    Optional<ExchangeBinding> resolve(String exchangeId, CounterpartyRole role);

    /** Resolves the v3 {@code exchangeId} for a {@code certificateId} reported by the given peer. */
    Optional<String> exchangeFor(String certificateId, String peerBpn);
}
