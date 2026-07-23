package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.KnownCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Store of the consumer's lifecycle view of certificates it has learned about — a Spring Data JPA
 * repository ({@code findAll} / {@code save} inherited). Keyed by a surrogate id; a tenant's view of a
 * certificate is looked up by {@code (participantContextId, certificateId)}.
 */
public interface ConsumerCertificateStore extends JpaRepository<KnownCertificate, String> {

    /** The calling tenant's view of a certificate (the provider-assigned {@code certificateId} is per-tenant). */
    Optional<KnownCertificate> findByParticipantContextIdAndCertificateId(String participantContextId, String certificateId);
}
