package org.metaform.certo.common.security;

import org.metaform.certo.common.pc.ParticipantContext;

/**
 * The tenant context for one outbound CCM call, threaded explicitly (no ambient state): the {@code sender}
 * participant context (whose identity stamps the message and whose id keys the siglet cache), the receiving
 * counterparty's {@code counterpartyBpn} (the message subject / v2.4.0 receiver) and its {@code counterpartyDid}
 * (the token audience), and the live {@code flowId}. The DID is always supplied by the caller — either named on
 * the driving management request or captured from the verified inbound peer that opened the exchange — so no
 * component ever resolves a DID from a BPN.
 */
public record OutboundCall(ParticipantContext sender, String counterpartyBpn, String counterpartyDid, String flowId) {
}
