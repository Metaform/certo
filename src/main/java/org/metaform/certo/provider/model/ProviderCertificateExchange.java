package org.metaform.certo.provider.model;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The provider's record of a {@code Certificate Exchange} (CX-0135 &sect;2.1) — one end-to-end
 * delivery interaction for a specific (certificateId, version), correlated by {@code exchangeId}.
 *
 * <p>Enforces the CX-0135 &sect;2.1.3 state machine: Fulfillment transitions must be legal, terminal
 * states are immutable, and Acceptance may only be recorded once the exchange is {@code FULFILLED}.
 * For asynchronous fulfillment it also carries a {@code plan} of remaining Fulfillment steps that the
 * provider works through.
 */
public class ProviderCertificateExchange {

    private final String exchangeId;
    private final String certificateId;
    private final int version;
    private final String counterpartyBpn;

    private FulfillmentStatus fulfillmentStatus;
    private List<StatusError> fulfillmentErrors;

    private AcceptanceStatus acceptanceStatus;
    private List<StatusError> acceptanceErrors;

    private final Deque<FulfillmentStatus> plan = new ArrayDeque<>();
    private boolean willFail;
    private boolean consumerInitiated;

    public ProviderCertificateExchange(String exchangeId, String certificateId, int version, String counterpartyBpn,
                               FulfillmentStatus initialStatus) {
        this(exchangeId, certificateId, version, counterpartyBpn, initialStatus, null);
    }

    public ProviderCertificateExchange(String exchangeId, String certificateId, int version, String counterpartyBpn,
                               FulfillmentStatus initialStatus, List<StatusError> initialErrors) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.version = version;
        this.counterpartyBpn = counterpartyBpn;
        this.fulfillmentStatus = initialStatus;
        this.fulfillmentErrors = initialErrors;
    }

    /** Advances the Fulfillment phase, rejecting illegal transitions and changes to a terminal state (409). */
    public void transitionFulfillment(FulfillmentStatus to, List<StatusError> errors) {
        if (!fulfillmentStatus.allowedNext().contains(to)) {
            throw ApiException.conflict("Illegal fulfillment transition " + fulfillmentStatus + " -> " + to
                    + " for exchange " + exchangeId);
        }
        this.fulfillmentStatus = to;
        this.fulfillmentErrors = errors;
    }

    /**
     * Records an Acceptance-phase outcome (CX-0135 &sect;4.4.4). The exchange must be {@code FULFILLED}
     * first (&sect;2.1.2), and transitions out of a terminal acceptance state are rejected (409). The
     * non-terminal {@code RETRIEVED} status is optional, so the first status recorded may be a terminal
     * verdict reached directly from {@code FULFILLED} (&sect;2.1.3); the provider never requires a prior
     * {@code RETRIEVED}.
     */
    public void recordAcceptance(AcceptanceStatus to, List<StatusError> errors) {
        if (fulfillmentStatus != FulfillmentStatus.FULFILLED) {
            throw ApiException.conflict("Exchange " + exchangeId + " cannot be accepted before it is FULFILLED"
                    + " (current fulfillment status: " + fulfillmentStatus + ")");
        }
        if (acceptanceStatus != null && !acceptanceStatus.allowedNext().contains(to)) {
            throw ApiException.conflict("Illegal acceptance transition " + acceptanceStatus + " -> " + to
                    + " for exchange " + exchangeId);
        }
        this.acceptanceStatus = to;
        this.acceptanceErrors = errors;
    }

    // --- asynchronous fulfillment plan ---------------------------------------------------------

    /** Records the remaining Fulfillment steps the provider will work through (in order). */
    public void planSteps(FulfillmentStatus... steps) {
        Collections.addAll(plan, steps);
    }

    /** Marks this exchange to terminate in {@code FAILED} when fulfillment would otherwise complete. */
    public void markWillFail() {
        this.willFail = true;
    }

    public boolean willFail() {
        return willFail;
    }

    /** Whether the consumer opened this exchange (so the provider may push fulfillment status to it). */
    public void markConsumerInitiated() {
        this.consumerInitiated = true;
    }

    public boolean isConsumerInitiated() {
        return consumerInitiated;
    }

    public boolean hasPendingFulfillment() {
        return !plan.isEmpty();
    }

    public FulfillmentStatus pollNextStep() {
        return plan.poll();
    }

    // --- accessors -----------------------------------------------------------------------------

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    public int version() {
        return version;
    }

    public String counterpartyBpn() {
        return counterpartyBpn;
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
