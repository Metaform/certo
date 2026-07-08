package org.metaform.certo.protocol;

/**
 * The protocol binding of a Certificate Exchange: which wire-protocol version the counterparty speaks and
 * how to reach it. Recorded when an interaction originates over a non-native protocol (an inbound
 * request) or when a caller initiates one explicitly; the absence of a binding means the counterparty is
 * native v3 ({@link ProtocolVersions#NATIVE}). This is the per-exchange attribute outbound routing keys
 * on — tied to the {@code exchangeId} (with {@code certificateId} as a fallback for the window before the
 * exchangeId is assigned).
 *
 * @param exchangeId    the v3 exchange this binding applies to (may be null until it is assigned)
 * @param certificateId the certificate id (== a legacy {@code documentId}); the fallback key
 * @param version       the counterparty's protocol version (see {@link ProtocolVersions})
 * @param role          whether the counterparty is a consumer or a provider
 * @param peerBpn       the counterparty's BPN
 * @param messageId     the originating message id, if any (for idempotency)
 * @param callbackUrl   the counterparty's <b>data-plane</b> endpoint to deliver outbound messages to (may
 *                      be null). Assumed to be the data-plane URL directly; in production it is resolved
 *                      out-of-band from the peer's DSP control-plane endpoint (see {@code Ccm240OutboundClient})
 */
public record ExchangeBinding(String exchangeId, String certificateId, String version,
                              CounterpartyRole role, String peerBpn, String messageId, String callbackUrl) {
}
