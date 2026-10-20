package org.metaform.certo.common.security.outbound;

import org.metaform.certo.common.pc.domain.ParticipantContext;

/**
 * The tenant context for one outbound protocol call, threaded explicitly (no ambient state): the
 * {@code sender} participant context (whose identity stamps the message and whose id keys the siglet
 * cache), the receiving {@code counterpartyId} (the counterparty's DID — the token audience), and the live
 * {@code flowId}. The counterparty id is always supplied by the caller — either named on the driving
 * management request or captured from the verified inbound peer that opened the exchange.
 */
public record OutboundCall(ParticipantContext sender, String counterpartyId, String flowId) {
}
