package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.KnownCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store of the consumer's lifecycle view of certificates it has learned about — a Spring Data JPA
 * repository ({@code findById} / {@code save} inherited).
 */
public interface ConsumerCertificateStore extends JpaRepository<KnownCertificate, String> {
}
