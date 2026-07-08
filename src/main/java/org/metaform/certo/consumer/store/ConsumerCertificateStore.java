package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.KnownCertificate;

import java.util.Optional;

/**
 * Store of the consumer's lifecycle view of certificates it has learned about. The port;
 * {@code InMemoryConsumerCertificateStore} is the default (in-memory) adapter, selectable via
 * {@code certo.persistence}.
 */
public interface ConsumerCertificateStore {

    void save(KnownCertificate certificate);

    Optional<KnownCertificate> find(String certificateId);
}
