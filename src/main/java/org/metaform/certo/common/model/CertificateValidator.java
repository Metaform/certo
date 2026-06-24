package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The party that can validate a certificate's information (CX-0135 &sect;4). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateValidator(String validatorName, String validatorBpn) {
}
