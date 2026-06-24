package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The authority that issued a certificate (CX-0135 &sect;4). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateIssuer(String issuerName, String issuerBpn) {
}
