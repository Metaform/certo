package org.metaform.certo.provider.dto;

/**
 * The body of the management "notify" call — a single-target push of a certificate lifecycle event to
 * <b>one</b> explicitly-named consumer. Certo keeps no registry of counterparties and decides nothing about
 * who is notified: to reach several consumers, the caller notifies once per consumer, and each call's
 * outcome is returned synchronously so a failure is retried per counterparty.
 *
 * <p>The event itself is derived, not chosen: the certificate's current lifecycle state supplies the
 * status, and the latest revision supplies the triage fields ({@code WITHDRAWN} carries the id alone).
 * <ul>
 *   <li>{@code messageId} — <b>required</b>; a UUID identifying this logical notification, used verbatim as
 *       the pushed event's {@code id}. A <b>retry MUST reuse the same {@code messageId}</b> (the consumer
 *       then treats the re-send as a duplicate of the same event); a genuinely new notification MUST carry
 *       a new one. Identity across retries is the caller's to manage, like the {@code messageId} of a
 *       certificate request;</li>
 *   <li>{@code protocol} — <b>required</b>; the consumer's wire-protocol id (an open string, e.g.
 *       {@code "sce-1.0"}). There is no default: the caller states the counterparty's protocol;</li>
 *   <li>{@code consumerId} — the target consumer's identity (its DID, the token audience);</li>
 *   <li>{@code flowId} — the live siglet flow for this push. <b>Required</b>; the token and the counterparty
 *       endpoint are resolved from the siglet cache keyed by it.</li>
 * </ul>
 * Blank strings normalize to {@code null}.
 */
public record NotifyRequest(
        String messageId,
        String protocol,
        String consumerId,
        String flowId) {

    public NotifyRequest {
        messageId = blankToNull(messageId);
        protocol = blankToNull(protocol);
        consumerId = blankToNull(consumerId);
        flowId = blankToNull(flowId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
