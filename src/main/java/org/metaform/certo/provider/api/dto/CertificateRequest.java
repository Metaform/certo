package org.metaform.certo.provider.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for {@code POST /certificate-requests} (CX-0135 &sect;4.4.1).
 *
 * @param certificateType opaque certificate type to request, e.g. {@code ISO9001} (mandatory)
 * @param locationBpns    BPNs the request applies to; if omitted, applies to the legal entity
 */
public record CertificateRequest(
        @NotBlank String certificateType,
        List<String> locationBpns) {
}
