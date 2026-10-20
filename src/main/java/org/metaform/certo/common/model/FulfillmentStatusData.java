package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateFulfillmentStatus} event, pushed by a provider to a
 * consumer as the push counterpart of fulfillment-status polling.
 *
 * @param exchangeId    identifier of the exchange whose fulfillment status is reported (mandatory)
 * @param certificateId the certificate the exchange concerns; MUST be present when status is FULFILLED
 * @param revision      the certificate revision the exchange concerns; MUST be present when status is FULFILLED
 * @param status        the fulfillment status (mandatory)
 * @param errors        error details; MUST be present and non-empty when status is DECLINED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FulfillmentStatusData(
        String exchangeId,
        String certificateId,
        Integer revision,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
