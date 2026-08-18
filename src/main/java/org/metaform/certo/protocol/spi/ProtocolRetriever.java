package org.metaform.certo.protocol.spi;

import org.metaform.certo.consumer.spi.CertificateRetriever;
import org.metaform.certo.protocol.ProtocolVersion;

/**
 * A version-specific {@link CertificateRetriever}: pulls a certificate from a provider's data plane in the
 * shape of one CX-0135 wire-protocol version. Registered under its {@link #version()} and selected per
 * exchange by {@link org.metaform.certo.protocol.DispatchingCertificateRetriever} — the retrieval analogue
 * of {@link ProtocolNotifier} / {@link ProtocolAcceptanceReporter}. The core service depends only on the
 * neutral {@link CertificateRetriever} port and is unaware of versions.
 */
public interface ProtocolRetriever extends CertificateRetriever {

    /** The wire-protocol version this retriever pulls in. */
    ProtocolVersion version();
}
