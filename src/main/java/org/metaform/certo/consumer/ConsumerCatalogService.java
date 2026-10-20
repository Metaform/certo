package org.metaform.certo.consumer;

import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.domain.KnownCertificate;
import org.metaform.certo.consumer.store.ConsumerCertificateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer's <b>known-certificate view</b>: its lifecycle record of certificates it has learned about
 * from inbound {@code CertificateLifecycleStatus} events (the consumer analogue of the provider's catalog).
 * The exchange lifecycle lives in {@link ConsumerExchangeService}, whose inbound handling calls
 * {@link #recordKnownCertificate} to keep this view in sync.
 */
@Service
@Transactional
public class ConsumerCatalogService {
    private final ConsumerCertificateStore certificateStore;

    public ConsumerCatalogService(ConsumerCertificateStore certificateStore) {
        this.certificateStore = certificateStore;
    }

    /** Returns the consumer's lifecycle view of a certificate its tenant has learned about. */
    @Transactional(readOnly = true)
    public KnownCertificate getKnownCertificate(String contextId, String certificateId) {
        return certificateStore.findByParticipantContextIdAndCertificateId(contextId, certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
    }

    /**
     * Creates or updates the <b>calling tenant's</b> lifecycle view of the certificate from a lifecycle
     * event. Runs inside the caller's transaction; the {@code (participantContextId, certificateId)} unique
     * constraint serializes concurrent creates. Updates mutate the managed {@code certifiedLocations}
     * collection in place (see {@link KnownCertificate#apply}). A different tenant that later learns about the
     * same certificate gets its own row rather than mutating this one.
     */
    public void recordKnownCertificate(LifecycleStatusData data, String contextId) {
        var c = data.certificate();
        certificateStore.findByParticipantContextIdAndCertificateId(contextId, c.certificateId())
                .ifPresentOrElse(
                        known -> {
                            known.apply(c.revision(), data.status(), c.certificateType(),
                                    c.validFrom(), c.validUntil(), c.certifiedLocations());
                            certificateStore.save(known);
                        },
                        () -> certificateStore.save(new KnownCertificate(c.certificateId(), contextId, c.revision(),
                                data.status(), c.certificateType(), c.validFrom(), c.validUntil(), c.certifiedLocations())));
    }
}
