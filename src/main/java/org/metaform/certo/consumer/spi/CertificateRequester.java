package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.security.outbound.OutboundCall;

import java.io.IOException;
import java.util.List;

/**
 * Outbound port: open and poll certificate requests on a provider's data plane (the consumer-initiated
 * "pull" half of the protocol). The core {@code ConsumerExchangeService} depends on this port; each
 * protocol adapter supplies an implementation under {@code org.metaform.certo.protocol.<name>}.
 */
public interface CertificateRequester {

    /**
     * Opens a certificate request and returns the opened exchange's identity and fulfillment status.
     * {@code messageId} identifies the request message; the provider's exchange creation is idempotent on
     * (counterparty, {@code messageId}), so a retry reuses it. {@code call} carries the sender participant
     * context, the counterparty identity, and the live outbound flow the secured adapter resolves its
     * token + endpoint from.
     */
    ProviderRequestResult request(String messageId, String certificateType, List<LocationFilter> certifiedLocations, OutboundCall call) throws IOException;

    /** Polls the fulfillment status of a previously opened exchange (see {@code call} above). */
    ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException;
}
