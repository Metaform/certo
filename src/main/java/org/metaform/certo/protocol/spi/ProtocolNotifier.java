package org.metaform.certo.protocol.spi;

import org.metaform.certo.protocol.DispatchingConsumerNotifier;
import org.metaform.certo.protocol.domain.ExchangeBinding;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.outbound.OutboundCall;

/**
 * A protocol-specific renderer/sender for provider &rarr; consumer notifications. One implementation per
 * protocol registers itself under {@link #protocolId()}; {@link DispatchingConsumerNotifier} selects
 * the right one from the exchange's {@link ExchangeBinding} and delegates. Each adapter renders its own wire
 * format; the token and counterparty endpoint are resolved from the siglet cache via the {@link OutboundCall}'s
 * flow. The {@code binding} is passed through for adapters that need its per-exchange detail; the SCE adapter
 * does not use it.
 */
public interface ProtocolNotifier {

    /** The open protocol id this adapter speaks (e.g. {@code "sce-1.0"}). */
    String protocolId();

    /**
     * Delivers a lifecycle notification to the consumer. {@code binding} is null for the native protocol. The
     * {@link OutboundCall} carries the sender context and the live flow (adapters resolve the token + endpoint
     * from it). Returns {@code true} on successful delivery.
     */
    boolean notifyLifecycle(ExchangeBinding binding, String messageId, LifecycleStatusData data, OutboundCall call);

    /**
     * Delivers a fulfillment-status notification to the consumer. {@code binding} is null for the native
     * protocol. The {@link OutboundCall} carries the sender context and the live flow. Returns {@code true} on
     * successful delivery (or intentional suppression where the target protocol has no equivalent).
     */
    boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call);
}
