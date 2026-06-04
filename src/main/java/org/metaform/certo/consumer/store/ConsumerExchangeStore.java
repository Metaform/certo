package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.ConsumerExchange;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of consumer-side exchange records and their acceptance decisions (demo only). */
@Component
public class ConsumerExchangeStore {

    private final ConcurrentMap<String, ConsumerExchange> exchanges = new ConcurrentHashMap<>();

    public void save(ConsumerExchange exchange) {
        exchanges.put(exchange.exchangeId(), exchange);
    }

    public Optional<ConsumerExchange> find(String exchangeId) {
        return Optional.ofNullable(exchanges.get(exchangeId));
    }
}
