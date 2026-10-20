package org.metaform.certo.protocol.sce.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The SCE wire payload of a {@code CertificateFulfillmentStatus} CloudEvent — the {@code data} member: the
 * Exchange representation restricted to the terminal Fulfillment-phase states. {@code certificate} is the
 * full wire {@link SceCertificate} when {@code status} is {@code FULFILLED}; the core works with the
 * neutral {@code FulfillmentStatusData} (domain), and the SCE notify/receive adapters map between them via
 * {@link org.metaform.certo.protocol.sce.SceCertificateCodec}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceFulfillmentStatus(
        String exchangeId,
        FulfillmentStatus status,
        SceCertificate certificate,
        List<StatusError> errors) {
}
