package org.metaform.certo.consumer.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.consumer.model.ConsumerCertificateExchange;

import java.util.List;

/** The consumer's view of a request it opened: the exchange identity and its fulfillment status. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumerRequestView(
        String exchangeId,
        String certificateId,
        int version,
        FulfillmentStatus fulfillmentStatus,
        List<StatusError> errors) {

    public static ConsumerRequestView of(ConsumerCertificateExchange exchange) {
        return new ConsumerRequestView(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.version(),
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors());
    }
}
