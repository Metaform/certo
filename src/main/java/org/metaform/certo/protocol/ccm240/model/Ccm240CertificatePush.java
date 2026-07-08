package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A legacy v2.4.0 {@code POST /companycertificate/push} message (provider &rarr; consumer): the full
 * certificate pushed inline as a {@code BusinessPartnerCertificate} 3.1.0 record. The adapter
 * up-converts the content to the v3 model and ingests it (see {@code Ccm240ConsumerController}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240CertificatePush(Ccm240Header header, BusinessPartnerCertificate31 content) {
}
