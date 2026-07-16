package org.metaform.certo.consumer;

import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.model.KnownCertificate;
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
    public KnownCertificate getKnownCertificate(String participantContextId, String certificateId) {
        return certificateStore.find(certificateId)
                .filter(c -> participantContextId.equals(c.participantContextId()))
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
    }

    /**
     * The next revision to assign to a certificate pushed over a protocol that carries no revision (v2.4.0):
     * one more than the tenant's currently-known revision for {@code certificateId}, or {@code 1} if the tenant
     * has not seen it before. A protocol adapter that mints a <b>stable</b> {@code certificateId} for a
     * re-pushed certificate uses this so an updated push becomes a new <em>revision</em> of the same known
     * certificate rather than a duplicate record.
     */
    @Transactional(readOnly = true)
    public int nextPushedRevision(String participantContextId, String certificateId) {
        return certificateStore.find(certificateId)
                .filter(c -> participantContextId != null && participantContextId.equals(c.participantContextId()))
                .map(c -> (c.revision() == null ? 1 : c.revision()) + 1)
                .orElse(1);
    }

    /**
     * Creates or updates the consumer tenant's lifecycle view of the certificate from a lifecycle event.
     * Runs inside the caller's transaction; the {@code certificateId} primary key serializes concurrent
     * creates. Updates mutate the managed {@code certifiedLocations} collection in place (see
     * {@link KnownCertificate#apply}).
     */
    public void recordKnownCertificate(LifecycleStatusData data, String participantContextId) {
        CertificateRecord c = data.certificate();
        certificateStore.find(c.certificateId()).ifPresentOrElse(
                known -> {
                    known.apply(c.revision(), data.status(), c.certificateType(),
                            c.validFrom(), c.validUntil(), c.certifiedLocations());
                    certificateStore.save(known);
                },
                () -> certificateStore.save(new KnownCertificate(c.certificateId(), participantContextId, c.revision(),
                        data.status(), c.certificateType(), c.validFrom(), c.validUntil(), c.certifiedLocations())));
    }
}
