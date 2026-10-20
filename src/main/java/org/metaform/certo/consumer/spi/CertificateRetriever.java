package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.security.outbound.OutboundCall;

import java.io.IOException;

/**
 * Outbound port: retrieve a certificate (and its referenced document binaries) from a provider's data
 * plane. The core {@code ConsumerExchangeService} depends on this port; each protocol adapter supplies
 * an implementation under {@code org.metaform.certo.protocol.<name>}.
 */
public interface CertificateRetriever {

    /**
     * Fetches a certificate's latest-revision metadata and all its referenced documents. {@code call}
     * carries the sender participant context, the counterparty identity, and the live outbound
     * flow the secured adapter resolves its token + endpoint from.
     *
     * @throws IOException on transport failure or a non-2xx response
     */
    /**
     * Pulls the certificate — pinned to {@code revision} when given (an exchange-scoped retrieve pins to
     * the revision the exchange concerns, since the certificate may have been modified meanwhile), the
     * latest otherwise.
     */
    RetrievedCertificate fetch(String certificateId, Integer revision, OutboundCall call) throws IOException;
}
