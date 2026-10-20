package org.metaform.certo.protocol.sce.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.LocationRole;

import java.time.LocalDate;
import java.util.List;

/**
 * The SCE wire representation of a certificate record (served by {@code GET /certificates/{id}} and
 * returned by search). This is the <em>wire</em> type, kept separate from the version-neutral domain model
 * ({@link org.metaform.certo.common.model.CertificateRecord});
 * {@link org.metaform.certo.protocol.sce.SceCertificateCodec} maps between them, so a future wire change
 * lands here and in the codec — not in the core.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceCertificate(
        String certificateId,
        Integer revision,
        String certificateType,
        String certificateTypeVersion,
        String registrationNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        String areaOfApplication,
        List<Location> certifiedLocations,
        Issuer issuer,
        Validator validator,
        List<Document> documents) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(String legalEntityId, String siteId, String addressId, LocationRole locationRole,
                           String areaOfApplication) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issuer(String issuerName, String issuerId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validator(String validatorName, String validatorId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String documentId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate createdDate,
            String language,
            String mediaType) {
    }
}
