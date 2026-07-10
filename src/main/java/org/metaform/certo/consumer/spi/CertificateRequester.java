package org.metaform.certo.consumer.spi;

import java.io.IOException;
import java.util.List;

/**
 * Outbound port: open and poll certificate requests on a provider's data plane (the consumer-initiated
 * "pull" half of the protocol). The core {@code ConsumerCertificateService} depends on this port; the v3
 * adapter implementation is {@code org.metaform.certo.protocol.ccm300.consumer.Ccm300Requester}.
 */
public interface CertificateRequester {

    /** Opens a certificate request and returns the opened exchange's identity and fulfillment status. */
    ProviderRequestResult request(String certificateType, List<String> certifiedLocations) throws IOException;

    /** Polls the fulfillment status of a previously opened exchange. */
    ProviderRequestResult pollStatus(String exchangeId) throws IOException;
}
