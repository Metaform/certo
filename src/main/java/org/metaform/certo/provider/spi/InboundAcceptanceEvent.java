package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.AcceptanceStatusData;

/**
 * One inbound consumer&rarr;provider acceptance event, decoded by a protocol adapter to the neutral
 * domain payload and handed to the core. The adapter owns the wire and supplies the {@code dedupKey}
 * (for a CloudEvents protocol, {@code source} + {@code id}); the core owns validation, the counterparty
 * guard, and the state change.
 */
public record InboundAcceptanceEvent(AcceptanceStatusData data, String dedupKey) {
}
