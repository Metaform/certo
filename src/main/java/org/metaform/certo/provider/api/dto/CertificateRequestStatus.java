package org.metaform.certo.provider.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Current fulfillment status of a certificate request (CX-0135 &sect;4.4.2), returned from
 * {@code GET /certificate-requests/{id}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateRequestStatus(
        String exchangeId,
        String certificateId,
        int version,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
