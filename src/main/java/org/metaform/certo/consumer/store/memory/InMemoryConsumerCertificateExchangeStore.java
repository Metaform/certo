package org.metaform.certo.consumer.store.memory;

import org.metaform.certo.consumer.store.ConsumerCertificateExchangeStore;

import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of consumer-side exchange records and their acceptance decisions (non-persistent). */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryConsumerCertificateExchangeStore implements ConsumerCertificateExchangeStore {

    private final ConcurrentMap<String, ConsumerCertificateExchange> exchanges = new ConcurrentHashMap<>();

    public void save(ConsumerCertificateExchange exchange) {
        exchanges.put(exchange.exchangeId(), exchange);
    }

    public Optional<ConsumerCertificateExchange> find(String exchangeId) {
        return Optional.ofNullable(exchanges.get(exchangeId));
    }
}
