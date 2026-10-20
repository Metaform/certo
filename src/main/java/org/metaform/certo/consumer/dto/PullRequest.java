package org.metaform.certo.consumer.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Trigger body for a proactive certificate pull — the consumer retrieves and displays a partner's
 * certificate without a prior notification (the SCE specification v2 asset-based pull, consumer-initiated). Not part of
 * the SCE specification itself.
 *
 * <p>The consumer tenant is named in the request path. {@code partnerId} names the
 * provider (its DID, the token audience). {@code flowId} is the live
 * outbound flow — established upstream (EDC catalog / negotiation / transfer) — to read the certificate over.
 * {@code protocol} is <b>required</b>: the partner's wire-protocol id (an open string); there is no
 * default.
 */
public record PullRequest(
        @NotBlank String partnerId,
        @NotBlank String flowId,
        String protocol) {
}
