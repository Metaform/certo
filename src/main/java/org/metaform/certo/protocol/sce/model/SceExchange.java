package org.metaform.certo.protocol.sce.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.provider.dto.ExchangeView;

import java.util.List;

/**
 * The SCE wire representation of a Certificate Exchange. One {@code status} covers both phases: it is the
 * Acceptance-phase state once the consumer has reported one, else the Fulfillment-phase state.
 * {@code certificateId}/{@code revision} are present once assigned (never for {@code DECLINED});
 * {@code errors} carries the grounds of a negative outcome.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceExchange(
        String exchangeId,
        String certificateId,
        Integer revision,
        String status,
        List<StatusError> errors) {

    /** Renders the core's two-phase view as the single-status SCE Exchange object. */
    public static SceExchange of(ExchangeView view) {
        var acceptance = view.acceptanceStatus();
        var status = acceptance != null ? acceptance.name() : view.fulfillmentStatus().name();
        var errors = acceptance != null ? view.acceptanceErrors() : view.fulfillmentErrors();
        var yieldsCertificate = acceptance != null || view.fulfillmentStatus() != org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
        // No certificate identity before one is assigned (a pending exchange), and none for DECLINED.
        var hasCertificate = yieldsCertificate && view.certificateId() != null;
        return new SceExchange(
                view.exchangeId(),
                hasCertificate ? view.certificateId() : null,
                hasCertificate ? view.revision() : null,
                status,
                errors == null || errors.isEmpty() ? null : errors);
    }
}
