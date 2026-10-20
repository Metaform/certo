package org.metaform.certo.consumer.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The consumer's view of a provider request/poll response: the opened
 * exchange's identity and its current Fulfillment status. {@code certificate} carries whatever the
 * provider's Exchange object embedded — the full record at the {@code FULFILLED} hand-off (so no separate
 * retrieval is needed), the id/revision reference form otherwise, or {@code null} before assignment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderRequestResult(
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
