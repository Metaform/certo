package org.metaform.certo.provider.dto;

import jakarta.validation.constraints.NotBlank;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Request body for {@code POST /certificate-requests} (CX-0135 &sect;3.3.1).
 *
 * @param certificateType    opaque certificate type to request, e.g. {@code ISO9001} (mandatory)
 * @param certifiedLocations BPNs (BPNL/BPNS/BPNA) the request targets; if omitted, applies to the
 *                           legal entity. Never null — an omitted value is normalized to an empty list.
 */
public record CertificateRequest(@NotBlank String certificateType, @NotNull List<String> certifiedLocations) {

    public CertificateRequest {
        certifiedLocations = certifiedLocations == null ? List.of() : List.copyOf(certifiedLocations);
    }
}
