package org.metaform.certo.provider.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Response to an accepted certificate request (CX-0135 &sect;4.4.1), returned with {@code HTTP 202}.
 * Carries the assigned exchange and certificate identity and the initial fulfillment status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateRequestResponse(
        String exchangeId,
        String certificateId,
        int version,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
