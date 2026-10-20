package org.metaform.certo.protocol.store;

import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.domain.ExchangeBindingId;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Store of {@link ExchangeBinding}s — which protocol each exchange's counterparty speaks. A Spring Data
 * JPA repository keyed by {@code exchangeId}; the domain operations are thin default methods over the
 * inherited CRUD.
 */
public interface ExchangeBindingStore extends JpaRepository<ExchangeBinding, ExchangeBindingId> {

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
        return findById(new ExchangeBindingId(exchangeId, role));
    }

}
