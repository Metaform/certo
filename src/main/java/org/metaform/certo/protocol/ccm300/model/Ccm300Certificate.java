package org.metaform.certo.protocol.ccm300.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.LocationRole;

import java.time.LocalDate;
import java.util.List;

/**
 * The CX-0135 <b>v3</b> wire representation of a certificate (the {@code BusinessPartnerCertificate}
 * submodel served by {@code GET /certificates/{id}} and returned by search). This is the v3 <em>wire</em>
 * type, kept separate from the version-neutral domain model ({@link org.metaform.certo.common.model.CertificateRecord});
 * {@link org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec} maps between them. When BPC 4.0.0
 * publishes and its shape diverges from the domain (nested {@code type}, {@code issuerBpnl}, {@code uploader},
 * …), those changes land here and in the codec — not in the core.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm300Certificate(
        String certificateId,
        Integer revision,
        String certificateType,
        String certificateTypeVersion,
        String registrationNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        String trustLevel,
        String areaOfApplication,
        List<Location> certifiedLocations,
        Issuer issuer,
        Validator validator,
        List<Document> documents) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(String bpnl, String bpna, String bpns, LocationRole locationRole, String areaOfApplication) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issuer(String issuerName, String issuerBpn) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validator(String validatorName, String validatorBpn) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String documentId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate createdDate,
            String language,
            String mediaType,
            String contentBase64) {
    }
}
