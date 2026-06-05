package org.metaform.certo.consumer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The consumer's view of a provider request/poll response (CX-0135 &sect;4.4.1 / &sect;4.4.2): the opened
 * exchange's identity and its current Fulfillment status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderRequestResult(
        String exchangeId,
        String certificateId,
        int version,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
