package org.metaform.certo.protocol;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.OutboundCall;

/**
 * A version-specific renderer/sender for provider &rarr; consumer notifications. One implementation per
 * protocol version registers itself under {@link #version()}; {@link DispatchingConsumerNotifier} selects
 * the right one from the exchange's {@link ExchangeBinding}. The v3 implementation ignores the binding and
 * sends CloudEvents to the configured consumer; other versions render their own message to
 * {@code binding.callbackUrl()}.
 */
public interface ProtocolNotifier {

    /** The protocol version this adapter speaks (see {@link ProtocolVersion}). */
    ProtocolVersion version();

    /**
     * Delivers a lifecycle notification to the consumer. {@code binding} is null for the native version.
     * {@code flowId} is the live outbound flow (secured adapters resolve the token + endpoint from it);
     * {@code null} when security is disabled. Returns {@code true} on successful delivery.
     */
    boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data, OutboundCall call);

    /**
     * Delivers a fulfillment-status notification to the consumer. {@code binding} is null for the native
     * version. {@code flowId} is the live outbound flow (secured adapters resolve the token + endpoint from
     * it); {@code null} when security is disabled. Returns {@code true} on successful delivery (or intentional
     * suppression).
     */
    boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call);
}
