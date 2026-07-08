package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The {@code io.catenax.business_partner_certificate} <b>3.1.0</b> aspect model — the inline content of
 * a legacy v2.4.0 {@code /companycertificate/push} message. Field names mirror the published 3.1.0 JSON
 * schema; camelCase Java names carry a {@link JsonProperty} where the wire name diverges (e.g. the
 * document's {@code documentID}). {@link org.metaform.certo.protocol.ccm240.Ccm240Translation} converts between
 * this and certo's v3 {@link org.metaform.certo.common.model.CertificateRecord}.
 *
 * <p>Unlike v3 (metadata record + separate document binaries), 3.1.0 embeds a single {@code document}
 * with its {@code contentBase64} inline, and models locations as a holder {@code businessPartnerNumber}
 * plus a flat {@code enclosedSites} list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessPartnerCertificate31(
        String businessPartnerNumber,
        Type type,
        String registrationNumber,
        String areaOfApplication,
        List<EnclosedSite> enclosedSites,
        String validFrom,
        String validUntil,
        Issuer issuer,
        String trustLevel,
        Validator validator,
        String uploader,
        Document document) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Type(String certificateType, String certificateVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnclosedSite(String enclosedSiteBpn, String areaOfApplication) {
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
            String creationDate,
            @JsonProperty("documentID") String documentId,
            String contentType,
            String contentBase64) {
    }
}
