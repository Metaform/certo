package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The provider's full view of a {@code Certificate Exchange} — both phases. Management/inspection only
 * (not part of CX-0135); lets callers confirm that a consumer's acceptance callback was recorded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeView(
        String exchangeId,
        String certificateId,
        int revision,
        FulfillmentStatus fulfillmentStatus,
        List<StatusError> fulfillmentErrors,
        AcceptanceStatus acceptanceStatus,
        List<StatusError> acceptanceErrors) {
}
