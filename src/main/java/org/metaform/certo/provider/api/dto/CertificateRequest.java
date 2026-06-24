package org.metaform.certo.provider.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for {@code POST /certificate-requests} (CX-0135 &sect;3.3.1).
 *
 * @param certificateType        opaque certificate type to request, e.g. {@code ISO9001} (mandatory)
 * @param certifiedLocationBpns  BPNs (BPNL/BPNS/BPNA) the request targets; if omitted, applies to the
 *                               legal entity
 */
public record CertificateRequest(
        @NotBlank String certificateType,
        List<String> certifiedLocationBpns) {
}
