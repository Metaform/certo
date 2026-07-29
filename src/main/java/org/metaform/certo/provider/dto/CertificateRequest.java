package org.metaform.certo.provider.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for {@code POST /certificate-requests} (CX-0135 &sect;3.3.1).
 *
 * @param certificateType    opaque certificate type to request, e.g. {@code ISO9001} (mandatory)
 * @param certifiedLocations BPNs (BPNL/BPNS/BPNA) the request targets; an omitted value binds to an empty
 *                           list (CX-0135: no locations = the legal entity). Never null downstream.
 */
public record CertificateRequest(@NotBlank String certificateType, List<String> certifiedLocations) {

    public CertificateRequest {
        // Normalize an omitted/null value to an empty list so downstream request-key building is null-safe.
        certifiedLocations = certifiedLocations == null ? List.of() : certifiedLocations;
    }
}
