package org.metaform.certo.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.consumer.model.ConsumerCertificateExchange;

/**
 * A management view of one consumer-side exchange, for the reconciliation query. {@code embedded} tells a
 * client whether a {@code retrieve} will return inline content (an embedded push) or fetch from the provider.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumerExchangeView(
        String exchangeId,
        String certificateId,
        Integer revision,
        boolean consumerInitiated,
        FulfillmentStatus fulfillmentStatus,
        AcceptanceStatus acceptanceStatus,
        boolean embedded) {

    public static ConsumerExchangeView of(ConsumerCertificateExchange exchange) {
        return new ConsumerExchangeView(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.revision(),
                exchange.consumerInitiated(),
                exchange.fulfillmentStatus(),
                exchange.acceptanceStatus(),
                exchange.embeddedContent() != null);
    }
}
