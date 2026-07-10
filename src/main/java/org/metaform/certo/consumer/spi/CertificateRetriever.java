package org.metaform.certo.consumer.spi;

import java.io.IOException;

/**
 * Outbound port: retrieve a certificate (and its referenced document binaries) from a provider's data
 * plane. The core {@code ConsumerCertificateService} depends on this port; the v3 adapter implementation
 * is {@code org.metaform.certo.protocol.ccm300.consumer.Ccm300Retriever}.
 */
public interface CertificateRetriever {

    /**
     * Fetches a certificate's latest-revision metadata and all its referenced documents.
     *
     * @throws IOException on transport failure or a non-2xx response
     */
    RetrievedCertificate fetch(String certificateId) throws IOException;
}
