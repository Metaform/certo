package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateAcceptanceStatus} CloudEvent.
 * Reports an Acceptance-phase outcome for a {@code Certificate Exchange}. The {@code exchangeId} alone
 * identifies the certificate version the verdict applies to — the exchange pins it.
 *
 * @param exchangeId identifier of the exchange this event reports on (mandatory)
 * @param status     the acceptance status (mandatory)
 * @param errors     error details; MUST be present and non-empty when status is REJECTED, and MUST NOT be
 *                   present otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptanceStatusData(
        String exchangeId,
        AcceptanceStatus status,
        List<StatusError> errors) {
}
