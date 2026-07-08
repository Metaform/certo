package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.ConsumerCertificateExchange;

import java.util.Optional;

/**
 * Store of the consumer's {@code Certificate Exchange}es. The port; {@code InMemoryConsumerCertificateExchangeStore}
 * is the default (in-memory) adapter, selectable via {@code certo.persistence}.
 */
public interface ConsumerCertificateExchangeStore {

    void save(ConsumerCertificateExchange exchange);

    Optional<ConsumerCertificateExchange> find(String exchangeId);
}
