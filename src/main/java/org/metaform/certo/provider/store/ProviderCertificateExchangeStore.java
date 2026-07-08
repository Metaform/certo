package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.ProviderCertificateExchange;

import java.util.Optional;

/**
 * Store of the provider's {@code Certificate Exchange}es. The port; {@code InMemoryProviderCertificateExchangeStore}
 * is the default (in-memory) adapter, selectable via {@code certo.persistence}.
 */
public interface ProviderCertificateExchangeStore {

    void save(ProviderCertificateExchange exchange);

    Optional<ProviderCertificateExchange> find(String exchangeId);
}
