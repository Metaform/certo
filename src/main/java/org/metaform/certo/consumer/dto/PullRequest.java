package org.metaform.certo.consumer.dto;

import jakarta.validation.constraints.NotBlank;
import org.metaform.certo.protocol.ProtocolVersion;

/**
 * Trigger body for a proactive certificate pull — the consumer retrieves and displays a partner's
 * certificate without a prior notification (CX-0135 v2 asset-based pull, consumer-initiated). Not part of
 * CX-0135 itself.
 *
 * <p>The consumer tenant is named in the request path. {@code partnerBpn} and {@code partnerDid} name the
 * provider (its BPN, the message receiver, and its DID, the token audience). {@code flowId} is the live
 * outbound flow — established upstream (EDC catalog / negotiation / transfer) — to read the certificate over.
 * {@code protocolVersion} is the partner's wire version (e.g. {@code "2.4.0"}); defaults to native v3 when
 * omitted.
 */
public record PullRequest(
        @NotBlank String partnerBpn,
        @NotBlank String partnerDid,
        @NotBlank String flowId,
        ProtocolVersion protocolVersion) {
}
