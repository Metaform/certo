package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateFulfillmentStatus} event, pushed by a provider to a
 * consumer as the push counterpart of fulfillment-status polling.
 *
 * @param exchangeId  identifier of the exchange whose fulfillment status is reported (mandatory)
 * @param status      the fulfillment status (mandatory)
 * @param certificate the certificate the exchange concerns; MUST be present as the full record when status
 *                    is FULFILLED — the hand-off delivers the metadata, so the consumer needs no separate
 *                    retrieval (document binaries stay pull-only)
 * @param errors      error details; MUST be present and non-empty when status is DECLINED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FulfillmentStatusData(
        String exchangeId,
        FulfillmentStatus status,
        CertificateRecord certificate,
        List<StatusError> errors) {

    /** The referenced certificate's id, when one is assigned. */
    public String certificateId() {
        return certificate == null ? null : certificate.certificateId();
    }

    /** The referenced certificate's revision, when one is assigned. */
    public Integer revision() {
        return certificate == null ? null : certificate.revision();
    }
}
