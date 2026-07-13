package org.metaform.certo.protocol;

/**
 * The protocol binding of a Certificate Exchange: which wire-protocol version the counterparty speaks and
 * how to reach it. Recorded when an interaction originates over a non-native protocol (an inbound
 * request) or when a caller initiates one explicitly; the absence of a binding means the counterparty is
 * native v3 ({@link ProtocolVersion#NATIVE}). This is the per-exchange attribute outbound routing keys
 * on — keyed by the {@code exchangeId}; {@code certificateId} + {@code peerBpn} additionally correlate a
 * v2.4.0 {@code documentId} back to the exchange for inbound status.
 *
 * @param exchangeId    the v3 exchange this binding applies to (may be null until it is assigned)
 * @param certificateId the certificate id (== a v2.4.0 {@code documentId}); correlates status via the peer BPN
 * @param version       the counterparty's protocol version (see {@link ProtocolVersion})
 * @param role          whether the counterparty is a consumer or a provider
 * @param peerBpn       the counterparty's BPN
 * @param messageId     the originating message id, if any (for idempotency)
 * @param callbackUrl   the counterparty's endpoint to deliver outbound messages to (may be null)
 */
public record ExchangeBinding(String exchangeId, String certificateId, ProtocolVersion version,
                              CounterpartyRole role, String peerBpn, String messageId, String callbackUrl) {
}
