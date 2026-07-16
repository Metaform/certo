package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.security.OutboundCall;

import java.io.IOException;
import java.util.List;

/**
 * Outbound port: open and poll certificate requests on a provider's data plane (the consumer-initiated
 * "pull" half of the protocol). The core {@code ConsumerExchangeService} depends on this port; the v3
 * adapter implementation is {@code org.metaform.certo.protocol.ccm300.consumer.Ccm300Requester}.
 */
public interface CertificateRequester {

    /**
     * Opens a certificate request and returns the opened exchange's identity and fulfillment status.
     * {@code call} carries the sender participant context, the provider (counterparty) BPN, and the live
     * outbound flow the secured adapter resolves its token + endpoint from.
     */
    ProviderRequestResult request(String certificateType, List<String> certifiedLocations, OutboundCall call) throws IOException;

    /** Polls the fulfillment status of a previously opened exchange (see {@code call} above). */
    ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException;
}
