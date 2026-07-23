package org.metaform.certo.protocol;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Store of {@link ExchangeBinding}s — which protocol version each exchange's counterparty speaks and how
 * to reach it. A Spring Data JPA repository keyed by {@code exchangeId}; the domain operations are thin
 * default methods over the inherited CRUD plus one derived query.
 */
public interface ExchangeBindingStore extends JpaRepository<ExchangeBinding, String> {

    /** Persists a binding, but only once its {@code exchangeId} (the key) is assigned. */
    default void record(ExchangeBinding binding) {
        if (binding.exchangeId() != null) {
            save(binding);
        }
    }

    /**
     * Resolves the binding for an exchange whose counterparty plays the given role, by {@code exchangeId}.
     * A binding for the other role is ignored (an exchange has one counterparty per direction).
     */
    default Optional<ExchangeBinding> resolve(String exchangeId, CounterpartyRole role) {
        if (exchangeId == null) {
            return Optional.empty();
        }
        return findById(exchangeId).filter(binding -> binding.role() == role);
    }

    /** The secondary correlation: a v2.4.0 {@code documentId} (== certificateId) + the peer's verified DID. */
    Optional<ExchangeBinding> findByCertificateIdAndPeerDid(String certificateId, String peerDid);

    /**
     * Resolves the v3 {@code exchangeId} for a {@code certificateId} reported by the peer with the given
     * <b>verified</b> DID. Keying on the verified identity (not a self-declared header BPN) prevents a caller
     * from resolving another counterparty's exchange.
     */
    default Optional<String> exchangeFor(String certificateId, String peerDid) {
        if (certificateId == null || peerDid == null) {
            return Optional.empty();
        }
        return findByCertificateIdAndPeerDid(certificateId, peerDid).map(ExchangeBinding::exchangeId);
    }
}
