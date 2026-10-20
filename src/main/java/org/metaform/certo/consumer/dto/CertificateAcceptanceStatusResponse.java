package org.metaform.certo.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Current acceptance status of a {@code Certificate Exchange} on the consumer,
 * returned from the acceptance-status query. The {@code exchangeId} alone identifies the certificate
 * version the verdict applies to — the exchange pins it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateAcceptanceStatusResponse(
        String exchangeId,
        AcceptanceStatus status,
        List<StatusError> errors) {
}
