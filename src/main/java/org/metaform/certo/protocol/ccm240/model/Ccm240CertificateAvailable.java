package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A legacy v2.4.0 {@code POST /companycertificate/available} message (provider &rarr; consumer): the
 * provider notifies that a certificate is available, by reference only (no inline content). Retrieving
 * the content required the legacy per-asset EDC pull, which is out of scope for this adapter — old
 * providers should use {@code /push} instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240CertificateAvailable(Ccm240Header header, Content content) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String documentId, String certificateType, List<String> locationBpns) {
    }
}
