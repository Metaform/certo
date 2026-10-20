package org.metaform.certo.consumer.dto;

import jakarta.validation.constraints.NotBlank;
import org.metaform.certo.common.model.LocationFilter;

import java.util.List;

/**
 * Trigger body for the consumer to open a certificate request on the provider (the
 * consumer-initiated "pull" flow, the SCE specification). Not part of the SCE specification itself.
 *
 * <p>The consumer tenant making the request is named in the request path
 * ({@code /management/v1/participant-contexts/{participantContextId}/consumer/certificate-requests}).
 * {@code providerId} names the target provider — its DID (the token audience). {@code flowId} is the
 * live outbound flow to call the provider over. {@code protocol} is <b>required</b>: the provider's
 * wire-protocol id (an open string, e.g. {@code "sce-1.0"}); there is no default. {@code messageId} is
 * <b>required</b>: a UUID identifying this logical request, carried on the wire so the provider's exchange
 * creation is idempotent — a retry MUST reuse it, a genuinely new request MUST carry a new one.
 */
public record InitiateRequest(
        @NotBlank String messageId,
        @NotBlank String providerId,
        @NotBlank String certificateType,
        List<LocationFilter> certifiedLocations,
        String flowId,
        String protocol) {
}
