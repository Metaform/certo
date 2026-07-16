package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.KnownCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Store of the consumer's lifecycle view of certificates it has learned about — a Spring Data JPA
 * repository. The domain-named {@link #find} is a thin alias over {@code findById}; {@code save} is inherited.
 */
public interface ConsumerCertificateStore extends JpaRepository<KnownCertificate, String> {

    default Optional<KnownCertificate> find(String certificateId) {
        return findById(certificateId);
    }
}
