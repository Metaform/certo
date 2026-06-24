package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The CX-0135 &sect;4 certificate record — the single wire representation of a certificate, shared by
 * {@code GET /certificates/{id}}, {@code POST /certificates/search}, and the {@code data.certificate}
 * member of a lifecycle notification (CX-0135 &sect;3.2.1 / &sect;3.3.2).
 *
 * <p>Which fields are populated depends on context. On retrieval and search the full record is present
 * (but never document {@code contentBase64}). In a lifecycle push under the baseline consumer subject
 * only the light-triage subset is sent ({@code certificateId}, {@code revision}, {@code certificateType},
 * {@code validFrom}, {@code validUntil}); a {@code WITHDRAWN} event carries only {@code certificateId}.
 * {@code @JsonInclude(NON_NULL)} lets a single type serialize every subset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateRecord(
        String certificateId,
        Integer revision,
        String certificateType,
        String certificateTypeVersion,
        String registrationNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        String trustLevel,
        String areaOfApplication,
        List<CertifiedLocation> certifiedLocations,
        CertificateIssuer issuer,
        CertificateValidator validator,
        List<CertificateDocument> documents) {

    /** The light-triage subset pushed in a {@code CREATED}/{@code MODIFIED} lifecycle event (push-pull). */
    public static CertificateRecord lightTriage(String certificateId, int revision, String certificateType,
                                                LocalDate validFrom, LocalDate validUntil) {
        return new CertificateRecord(certificateId, revision, certificateType, null, null,
                validFrom, validUntil, null, null, null, null, null, null);
    }

    /** The minimal record carried by a {@code WITHDRAWN} lifecycle event (CX-0135 &sect;3.2.1). */
    public static CertificateRecord idOnly(String certificateId) {
        return new CertificateRecord(certificateId, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
