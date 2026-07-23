package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.CertificateRecord;

import java.util.Optional;

/**
 * A read port for resolving a held certificate to its neutral {@link CertificateRecord} (metadata only, no
 * document binaries). Lets a protocol adapter enrich an outbound message with certificate detail the core's
 * event model doesn't carry — e.g. the v2.4.0 {@code available} notice, whose fulfillment event has only the
 * {@code certificateId}. Returns empty when the certificate is absent or not owned by the tenant.
 */
public interface HeldCertificateLookup {

    Optional<CertificateRecord> find(String participantContextId, String certificateId);
}
