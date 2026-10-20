package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The <b>version-neutral</b> domain representation of a certificate — the internal model the core works
 * in, independent of any wire protocol. Each protocol adapter maps it to/from its own wire shape via a
 * codec. Every certificate wire boundary crosses that codec — retrieval, search, the consumer pull, and
 * the lifecycle notification — so this type never serializes to the wire directly.
 *
 * <p>Which fields are populated depends on context. On retrieval and search the full record is present
 * (never document content — documents are references, fetched separately). In a lifecycle push only the
 * light-triage subset is sent ({@code certificateId}, {@code revision}, {@code certificateType},
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
        String areaOfApplication,
        List<CertifiedLocation> certifiedLocations,
        CertificateIssuer issuer,
        CertificateValidator validator,
        List<CertificateDocument> documents) {

    /** The light-triage subset pushed in an {@code AVAILABLE} lifecycle event. */
    public static CertificateRecord lightTriage(String certificateId, int revision, String certificateType,
                                                LocalDate validFrom, LocalDate validUntil) {
        return new CertificateRecord(certificateId, revision, certificateType, null, null,
                validFrom, validUntil, null, null, null, null, null);
    }

    /** The minimal record carried by a {@code WITHDRAWN} lifecycle event. */
    public static CertificateRecord idOnly(String certificateId) {
        return new CertificateRecord(certificateId, null, null, null, null, null, null, null, null, null, null, null);
    }
}
