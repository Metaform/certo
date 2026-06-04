package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateAcceptanceStatus} CloudEvent (CX-0135 &sect;4.4.4).
 * Reports an Acceptance-phase outcome for a {@code Certificate Exchange}.
 *
 * @param exchangeId    identifier of the exchange this event reports on (mandatory)
 * @param certificateId the certificate the status applies to (mandatory)
 * @param status        the acceptance status (mandatory)
 * @param errors        error details; MUST be present and non-empty when status is REJECTED or
 *                      ERRORED, and MUST NOT be present otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptanceStatusData(
        String exchangeId,
        String certificateId,
        AcceptanceStatus status,
        List<StatusError> errors) {
}
