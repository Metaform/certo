package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

/**
 * Store of the consumer's {@code Certificate Exchange}es — a Spring Data JPA repository. The domain-named
 * {@link #find} / {@link #all} are thin aliases over {@code findById} / {@code findAll}; {@code save} is
 * inherited. ({@code all} is used by the pending-exchange reconciliation query.)
 */
public interface ConsumerCertificateExchangeStore extends JpaRepository<ConsumerCertificateExchange, String> {

    default Optional<ConsumerCertificateExchange> find(String exchangeId) {
        return findById(exchangeId);
    }

    default Collection<ConsumerCertificateExchange> all() {
        return findAll();
    }
}
