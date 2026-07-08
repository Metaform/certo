package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateFulfillmentStatus} CloudEvent (CX-0135 &sect;4.3.2),
 * pushed by a provider to a consumer as the push counterpart of fulfillment-status polling.
 *
 * @param exchangeId    identifier of the exchange whose fulfillment status is reported (mandatory)
 * @param certificateId the certificate the exchange concerns (optional; absent e.g. on DECLINED/FAILED)
 * @param status        the fulfillment status (mandatory)
 * @param errors        error details; MUST be present and non-empty when status is DECLINED or FAILED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FulfillmentStatusData(
        String exchangeId,
        String certificateId,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
