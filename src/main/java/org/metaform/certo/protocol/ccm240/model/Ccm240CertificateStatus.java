package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A legacy v2.4.0 {@code POST /companycertificate/status} message (consumer &rarr; provider): feedback on
 * a consumed certificate. Maps to a v3 {@code CertificateAcceptanceStatus} CloudEvent on
 * {@code POST /certificate-acceptance-notifications}, correlated by the {@code documentId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240CertificateStatus(Ccm240Header header, Content content) {

    /**
     * @param documentId        the certificate (== v3 {@code certificateId}) the status applies to
     * @param certificateStatus the acceptance outcome
     * @param certificateErrors certificate-level errors (present for {@code REJECTED})
     * @param locationBpns       locations the status applies to
     * @param locationErrors     per-location errors
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            String documentId,
            Ccm240StatusValue certificateStatus,
            List<Ccm240Error> certificateErrors,
            List<String> locationBpns,
            List<Ccm240LocationError> locationErrors) {
    }
}
