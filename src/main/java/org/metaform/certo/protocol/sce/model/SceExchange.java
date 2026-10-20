package org.metaform.certo.protocol.sce.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.protocol.sce.SceCertificateCodec;
import org.metaform.certo.provider.dto.ExchangeView;

import java.util.List;

/**
 * The SCE wire representation of a Certificate Exchange. One {@code status} covers both phases: it is the
 * Acceptance-phase state once the consumer has reported one, else the Fulfillment-phase state.
 * {@code certificate} is the single slot for certificate identity: the full record exactly at
 * {@code FULFILLED} (the hand-off delivers the metadata, so no separate retrieval is needed), the
 * {@code certificateId}/{@code revision} reference form in every other state once assigned, and absent
 * before assignment or for {@code DECLINED}. {@code errors} carries the grounds of a negative outcome.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceExchange(
        String exchangeId,
        String status,
        SceCertificate certificate,
        List<StatusError> errors) {

    /** Renders the core's two-phase view as the single-status SCE Exchange object. */
    public static SceExchange of(ExchangeView view) {
        var acceptance = view.acceptanceStatus();
        var status = acceptance != null ? acceptance.name() : view.fulfillmentStatus().name();
        var errors = acceptance != null ? view.acceptanceErrors() : view.fulfillmentErrors();
        var yieldsCertificate = acceptance != null || view.fulfillmentStatus() != FulfillmentStatus.DECLINED;
        // No certificate identity before one is assigned (a pending exchange), and none for DECLINED.
        var hasCertificate = yieldsCertificate && view.certificateId() != null;
        SceCertificate certificate = null;
        if (hasCertificate) {
            // The full record exactly at the FULFILLED hand-off (when the view carries it — a withdrawn
            // certificate falls back to the reference form); the reference form everywhere else.
            certificate = view.certificate() != null
                    ? SceCertificateCodec.toWire(view.certificate())
                    : reference(view.certificateId(), view.revision());
        }
        return new SceExchange(
                view.exchangeId(),
                status,
                certificate,
                errors == null || errors.isEmpty() ? null : errors);
    }

    /** The reference form: only {@code certificateId} and {@code revision}. */
    public static SceCertificate reference(String certificateId, Integer revision) {
        return new SceCertificate(certificateId, revision, null, null, null, null, null, null, null, null, null, null);
    }

    /** The referenced certificate's id, when one is assigned. */
    public String certificateId() {
        return certificate == null ? null : certificate.certificateId();
    }

    /** The referenced certificate's revision, when one is assigned. */
    public Integer revision() {
        return certificate == null ? null : certificate.revision();
    }
}
