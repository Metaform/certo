package org.metaform.certo.consumer.spi;

/**
 * A neutral, protocol-agnostic notification that an inbound event was received and recorded on the
 * consumer's state. Emitted to {@link InboundNotificationListener}s so a plugged-in client can decide what
 * to do (typically drive the consumer management API to retrieve and accept, carrying its live
 * {@code flowId}). Carries no wire detail and no {@code flowId} — the flow is the client's to supply.
 *
 * @param kind            whether this is a lifecycle or a fulfillment event
 * @param exchangeId      the exchange a fulfillment event correlates to; null for a lifecycle event, which
 *                        is purely informational and never opens an exchange
 * @param certificateId   the certificate id, when known (may be null for a not-yet-fulfilled request)
 * @param revision        the certificate revision, when known
 * @param status          the status name ({@code LifecycleStatus} or {@code FulfillmentStatus})
 * @param counterpartyId  the authenticated sender's identity (its DID)
 */
public record InboundNotification(Kind kind,
                              String exchangeId,
                              String certificateId,
                              Integer revision,
                              String status,
                              String counterpartyId) {

    public enum Kind {
        LIFECYCLE,
        FULFILLMENT
    }
}
