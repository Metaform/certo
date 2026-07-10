package org.metaform.certo.consumer.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Demo trigger body for the consumer to open a certificate request on the provider (the
 * consumer-initiated "pull" flow, CX-0135 &sect;3.3.1). Not part of CX-0135 itself.
 */
public record InitiateRequest(
        @NotBlank String certificateType,
        List<String> certifiedLocations) {
}
