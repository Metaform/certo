package org.metaform.certo.protocol.spi;

import org.metaform.certo.consumer.spi.CertificateRetriever;

/**
 * A protocol-specific {@link CertificateRetriever}: pulls a certificate from a provider's data plane in the
 * shape of one wire protocol. Registered under its {@link #protocolId()} and selected per exchange by
 * {@link org.metaform.certo.protocol.DispatchingCertificateRetriever} — the retrieval analogue of
 * {@link ProtocolNotifier} / {@link ProtocolAcceptanceReporter}. The core service depends only on the
 * neutral {@link CertificateRetriever} port and is unaware of protocols.
 */
public interface ProtocolRetriever extends CertificateRetriever {

    /** The open protocol id this retriever pulls in (e.g. {@code "sce-1.0"}). */
    String protocolId();
}
