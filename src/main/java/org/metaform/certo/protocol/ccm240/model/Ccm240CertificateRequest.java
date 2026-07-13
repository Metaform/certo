package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A v2.4.0 {@code POST /companycertificate/request} message (consumer &rarr; provider). Maps to a
 * v3 {@code CertificateRequest} on {@code POST /certificate-requests}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240CertificateRequest(Ccm240Header header, Content content) {

    /**
     * @param certifiedBpn    the legal entity the requested certificate was issued for
     * @param certificateType the requested certificate type
     * @param locationBpns    BPNS/BPNA locations the request targets (map to v3 {@code certifiedLocations})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String certifiedBpn, String certificateType, List<String> locationBpns) {
    }
}
