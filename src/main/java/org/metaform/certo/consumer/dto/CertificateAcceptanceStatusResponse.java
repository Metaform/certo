package org.metaform.certo.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Current acceptance status of a {@code Certificate Exchange} on the consumer (CX-0135 &sect;4.3.3),
 * returned from {@code GET /certificate-acceptance-status/{id}}. The exchange is identified by
 * {@code exchangeId}; {@code certificateId} and {@code revision} identify the certificate version the
 * status applies to (informational).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateAcceptanceStatusResponse(
        String exchangeId,
        String certificateId,
        Integer revision,
        AcceptanceStatus status,
        List<StatusError> errors) {
}
