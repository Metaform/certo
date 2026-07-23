package org.metaform.certo.protocol;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.OutboundCall;

/**
 * A version-specific renderer/sender for provider &rarr; consumer notifications. One implementation per
 * protocol version registers itself under {@link #version()}; {@link DispatchingConsumerNotifier} selects
 * the right one from the exchange's {@link ExchangeBinding} and delegates. Each adapter renders its own wire
 * format; the token and counterparty endpoint are resolved from the siglet cache via the {@link OutboundCall}'s
 * flow. The {@code binding} is passed through for adapters that need its per-exchange detail (e.g. the v2.4.0
 * adapter); the v3 adapter does not use it.
 */
public interface ProtocolNotifier {

    /** The protocol version this adapter speaks (see {@link ProtocolVersion}). */
    ProtocolVersion version();

    /**
     * Delivers a lifecycle notification to the consumer. {@code binding} is null for the native version. The
     * {@link OutboundCall} carries the sender context and the live flow (adapters resolve the token + endpoint
     * from it). Returns {@code true} on successful delivery.
     */
    boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data, OutboundCall call);

    /**
     * Delivers a fulfillment-status notification to the consumer. {@code binding} is null for the native
     * version. The {@link OutboundCall} carries the sender context and the live flow. Returns {@code true} on
     * successful delivery (or intentional suppression where the target protocol has no equivalent).
     */
    boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call);
}
