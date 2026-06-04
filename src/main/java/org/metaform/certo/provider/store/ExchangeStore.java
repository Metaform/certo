package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.CertificateExchange;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of provider-side {@code Certificate Exchange} records (demo only). */
@Component
public class ExchangeStore {

    private final ConcurrentMap<String, CertificateExchange> exchanges = new ConcurrentHashMap<>();

    public void save(CertificateExchange exchange) {
        exchanges.put(exchange.exchangeId(), exchange);
    }

    public Optional<CertificateExchange> find(String exchangeId) {
        return Optional.ofNullable(exchanges.get(exchangeId));
    }
}
