package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store of the consumer's {@code Certificate Exchange}es — a Spring Data JPA repository ({@code findById} /
 * {@code findAll} / {@code save} inherited). {@code findAll} backs the pending-exchange reconciliation query.
 */
public interface ConsumerCertificateExchangeStore extends JpaRepository<ConsumerCertificateExchange, String> {
}
