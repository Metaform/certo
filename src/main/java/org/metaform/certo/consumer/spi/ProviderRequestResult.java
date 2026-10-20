package org.metaform.certo.consumer.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The consumer's view of a provider request/poll response: the opened
 * exchange's identity and its current Fulfillment status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderRequestResult(
        String exchangeId,
        String certificateId,
        Integer revision,
        FulfillmentStatus status,
        List<StatusError> errors) {
}
