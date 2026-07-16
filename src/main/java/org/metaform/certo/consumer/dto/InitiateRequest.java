package org.metaform.certo.consumer.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Trigger body for the consumer to open a certificate request on the provider (the
 * consumer-initiated "pull" flow, CX-0135 &sect;3.3.1). Not part of CX-0135 itself.
 *
 * <p>The consumer tenant making the request is named in the request path
 * ({@code /management/v1/participant-contexts/{participantContextId}/consumer/certificate-requests}).
 * {@code providerBpn} and {@code providerDid} name the target provider — its BPN (message subject) and its DID
 * (the token audience), supplied so no component has to resolve the DID from the BPN. {@code flowId} is the
 * live outbound flow to call the provider over.
 */
public record InitiateRequest(
        @NotBlank String providerBpn,
        @NotBlank String providerDid,
        @NotBlank String certificateType,
        List<String> certifiedLocations,
        String flowId) {
}
