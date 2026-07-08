package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Certificate;

import java.util.Collection;
import java.util.Optional;

/**
 * Store of certificate artifacts held by the provider. The port; {@code InMemoryProviderCertificateStore}
 * is the default (in-memory) adapter, selectable via {@code certo.persistence} so a JDBC/Postgres adapter
 * can replace it.
 */
public interface ProviderCertificateStore {

    void save(Certificate certificate);

    Optional<Certificate> find(String certificateId);

    Collection<Certificate> all();
}
