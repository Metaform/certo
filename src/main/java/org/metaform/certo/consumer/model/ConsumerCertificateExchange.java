package org.metaform.certo.consumer.model;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;

import java.util.List;

/**
 * The consumer's record of a {@code Certificate Exchange} it is party to (CX-0135 &sect;2.1), holding
 * both phases — like the provider's {@code ProviderCertificateExchange}. The consumer <em>mirrors</em> the
 * provider-owned Fulfillment status (tracked while it waits on a consumer-initiated request) and is
 * <em>authoritative</em> for the Acceptance status it reports.
 *
 * <p>{@code consumerInitiated} distinguishes the two ways an exchange is opened: a consumer request
 * (pull) versus a provider lifecycle {@code CREATED} notification (push, which enters directly at
 * {@code FULFILLED}). The Acceptance state machine is enforced; terminal states are immutable.
 */
public class ConsumerCertificateExchange {

    private final String exchangeId;
    private final String certificateId;
    private final Integer revision;
    private final boolean consumerInitiated;

    private FulfillmentStatus fulfillmentStatus;
    private List<StatusError> fulfillmentErrors;

    private AcceptanceStatus acceptanceStatus;
    private List<StatusError> acceptanceErrors;

    public ConsumerCertificateExchange(String exchangeId, String certificateId, Integer revision, boolean consumerInitiated,
                            FulfillmentStatus fulfillmentStatus, List<StatusError> fulfillmentErrors) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.revision = revision;
        this.consumerInitiated = consumerInitiated;
        this.fulfillmentStatus = fulfillmentStatus;
        this.fulfillmentErrors = fulfillmentErrors;
    }

    /** Mirrors a provider-reported Fulfillment status (from a poll or a pushed notification). */
    public void updateFulfillment(FulfillmentStatus status, List<StatusError> errors) {
        this.fulfillmentStatus = status;
        this.fulfillmentErrors = errors;
    }

    /**
     * Advances the Acceptance phase, rejecting illegal or post-terminal transitions (409). Reporting
     * {@code RETRIEVED} is optional, so the first status may be a terminal verdict reached directly from
     * {@code FULFILLED} (CX-0135 &sect;2.1.3).
     */
    public void transitionAcceptance(AcceptanceStatus status, List<StatusError> errors) {
        if (acceptanceStatus != null && !acceptanceStatus.allowedNext().contains(status)) {
            throw ApiException.conflict("Illegal acceptance transition " + acceptanceStatus + " -> " + status
                    + " for exchange " + exchangeId);
        }
        this.acceptanceStatus = status;
        this.acceptanceErrors = errors;
    }

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    public Integer revision() {
        return revision;
    }

    public boolean consumerInitiated() {
        return consumerInitiated;
    }

    public FulfillmentStatus fulfillmentStatus() {
        return fulfillmentStatus;
    }

    public List<StatusError> fulfillmentErrors() {
        return fulfillmentErrors;
    }

    public AcceptanceStatus acceptanceStatus() {
        return acceptanceStatus;
    }

    public List<StatusError> acceptanceErrors() {
        return acceptanceErrors;
    }
}
