package org.metaform.certo.consumer.spi;

/**
 * Extension point for reacting to inbound CCM notifications. After the consumer records an inbound
 * lifecycle/fulfillment event on its state, it emits an {@link InboundCcmEvent} to every registered
 * listener. A listener typically hands the event to a client that drives the consumer management API
 * (retrieve/accept) on its own timeline, supplying the live {@code flowId}.
 *
 * <p>The callback is fire-and-forget: it returns {@code void}, the app does not wait for or depend on any
 * result (the authoritative state update is the later management call), and a listener that throws must not
 * fail the inbound acknowledgement — the event is already recorded and can be reconciled via the
 * consumer's pending-exchange query.
 */
public interface InboundNotificationListener {

    void onNotification(InboundCcmEvent event);
}
