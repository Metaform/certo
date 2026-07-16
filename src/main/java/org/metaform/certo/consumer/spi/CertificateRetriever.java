package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.security.OutboundCall;

import java.io.IOException;

/**
 * Outbound port: retrieve a certificate (and its referenced document binaries) from a provider's data
 * plane. The core {@code ConsumerExchangeService} depends on this port; the v3 adapter implementation
 * is {@code org.metaform.certo.protocol.ccm300.consumer.Ccm300Retriever}.
 */
public interface CertificateRetriever {

    /**
     * Fetches a certificate's latest-revision metadata and all its referenced documents. {@code call}
     * carries the sender participant context, the provider (counterparty) BPN, and the live outbound
     * flow the secured adapter resolves its token + endpoint from.
     *
     * @throws IOException on transport failure or a non-2xx response
     */
    RetrievedCertificate fetch(String certificateId, OutboundCall call) throws IOException;
}
