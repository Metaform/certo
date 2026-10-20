package org.metaform.certo.provider.dto;

import jakarta.validation.constraints.NotBlank;
import org.metaform.certo.common.model.LocationFilter;

import java.util.List;

/**
 * The certificate request that opens a Certificate Exchange.
 *
 * @param messageId          a UUID identifying this message (not the exchange); creation is idempotent on
 *                           (counterparty, {@code messageId}) — a retry MUST reuse it, a new request MUST
 *                           carry a new one (mandatory)
 * @param certificateType    opaque certificate type to request, e.g. {@code ISO9001} (mandatory)
 * @param certifiedLocations location filter objects the request targets — the delivered certificate MUST
 *                           cover every one of them; an omitted value binds to an empty list (no locations
 *                           = the legal entity). Never null downstream.
 */
public record CertificateRequest(@NotBlank String messageId, @NotBlank String certificateType, List<LocationFilter> certifiedLocations) {

    public CertificateRequest {
        // Normalize an omitted/null value to an empty list so downstream request-key building is null-safe.
        certifiedLocations = certifiedLocations == null ? List.of() : certifiedLocations;
    }
}
