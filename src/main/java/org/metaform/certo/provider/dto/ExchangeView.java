package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The provider's full view of a {@code Certificate Exchange} — both phases. Management/inspection only
 * (not part of the SCE specification); lets callers confirm that a consumer's acceptance callback was recorded.
 *
 * <p>{@code certificate} carries the full record of the pinned (certificateId, revision) exactly while the
 * exchange sits at {@code FULFILLED} (the hand-off point) and the certificate has not been withdrawn; it is
 * what the SCE Exchange object embeds so the consumer needs no separate retrieval.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeView(
        String exchangeId,
        String certificateId,
        int revision,
        FulfillmentStatus fulfillmentStatus,
        List<StatusError> fulfillmentErrors,
        AcceptanceStatus acceptanceStatus,
        List<StatusError> acceptanceErrors,
        CertificateRecord certificate) {
}
