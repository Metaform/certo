package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Current fulfillment status of a certificate request, returned from
 * {@code GET /certificate-requests/{id}}. {@code certificateId} and {@code revision} are omitted for
 * {@code DECLINED} outcome, which never yields a certificate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateRequestStatus(
        String exchangeId,
        String certificateId,
        Integer revision,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
