package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of provider-side {@code Certificate Exchange} records (demo only). */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProviderCertificateExchangeStore implements ProviderCertificateExchangeStore {

    private final ConcurrentMap<String, ProviderCertificateExchange> exchanges = new ConcurrentHashMap<>();

    public void save(ProviderCertificateExchange exchange) {
        exchanges.put(exchange.exchangeId(), exchange);
    }

    public Optional<ProviderCertificateExchange> find(String exchangeId) {
        return Optional.ofNullable(exchanges.get(exchangeId));
    }
}
