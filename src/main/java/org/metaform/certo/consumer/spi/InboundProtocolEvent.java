package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;

/**
 * One inbound provider&rarr;consumer event, decoded by a protocol adapter to the neutral domain payload
 * and handed to the core. The adapter owns the wire (envelope parsing, event types, payload shapes) and
 * supplies the {@code dedupKey} that identifies the event for idempotent processing (for a CloudEvents
 * protocol, {@code source} + {@code id}); the core owns validation, state, and emission.
 */
public sealed interface InboundProtocolEvent {

    /** The protocol-derived idempotency key for this event (duplicates are skipped, batch-atomically). */
    String dedupKey();

    /** A certificate lifecycle event (informational; never touches an exchange). */
    record Lifecycle(LifecycleStatusData data, String dedupKey) implements InboundProtocolEvent {
    }

    /** A fulfillment-status event for an exchange this consumer opened. */
    record Fulfillment(FulfillmentStatusData data, String dedupKey) implements InboundProtocolEvent {
    }
}
