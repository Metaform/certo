package org.metaform.certo.provider.model;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;

import java.util.List;

/**
 * The provider's record of a {@code Certificate Exchange} (CX-0135 &sect;2.1) — one end-to-end
 * delivery interaction, correlated by {@code exchangeId}.
 *
 * <p>Enforces the CX-0135 &sect;2.1.3 state machine: Fulfillment transitions must be legal, terminal
 * states are immutable, and Acceptance may only be recorded once the exchange is {@code FULFILLED}.
 *
 * <p>A consumer-initiated request for a certificate the provider does not yet hold is opened as a
 * {@link #pending} exchange in {@code CERTIFICATION_REQUESTED} — awaiting a certification-authority
 * (backend) response — carrying the requested type and locations so a later issuance can be matched to
 * it. The certificate identity is unknown until the backend issues it, so {@code certificateId}/{@code
 * revision} are assigned at {@link #fulfill} time.
 */
public class ProviderCertificateExchange {

    private final String exchangeId;
    private final String counterpartyBpn;

    private String certificateId;
    private int revision;

    private FulfillmentStatus fulfillmentStatus;
    private List<StatusError> fulfillmentErrors;

    private AcceptanceStatus acceptanceStatus;
    private List<StatusError> acceptanceErrors;

    private boolean consumerInitiated;

    /** For a pending consumer request: what the consumer asked for, used to match a later issuance. */
    private String requestedType;
    private List<String> requestedLocations;

    public ProviderCertificateExchange(String exchangeId, String certificateId, int revision, String counterpartyBpn,
                               FulfillmentStatus initialStatus) {
        this(exchangeId, certificateId, revision, counterpartyBpn, initialStatus, null);
    }

    public ProviderCertificateExchange(String exchangeId, String certificateId, int revision, String counterpartyBpn,
                               FulfillmentStatus initialStatus, List<StatusError> initialErrors) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.revision = revision;
        this.counterpartyBpn = counterpartyBpn;
        this.fulfillmentStatus = initialStatus;
        this.fulfillmentErrors = initialErrors;
    }

    /**
     * Opens a consumer-initiated exchange that is waiting for the backend to issue a certificate: no
     * certificate identity yet, status {@code CERTIFICATION_REQUESTED}, and the requested type/locations
     * retained so an issuance can be matched to it.
     */
    public static ProviderCertificateExchange pending(String exchangeId, String counterpartyBpn,
                                                      String requestedType, List<String> requestedLocations) {
        var exchange = new ProviderCertificateExchange(
                exchangeId, null, 0, counterpartyBpn, FulfillmentStatus.CERTIFICATION_REQUESTED);
        exchange.consumerInitiated = true;
        exchange.requestedType = requestedType;
        exchange.requestedLocations = requestedLocations;
        return exchange;
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
     * Binds the issued certificate identity and transitions the exchange to {@code FULFILLED} (the
     * backend produced the certificate). Illegal from a terminal state (409).
     */
    public void fulfill(String certificateId, int revision) {
        this.certificateId = certificateId;
        this.revision = revision;
        transitionFulfillment(FulfillmentStatus.FULFILLED, null);
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

    /** Whether the consumer opened this exchange (so the provider may push fulfillment status to it). */
    public void markConsumerInitiated() {
        this.consumerInitiated = true;
    }

    public boolean isConsumerInitiated() {
        return consumerInitiated;
    }

    // --- accessors -----------------------------------------------------------------------------

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    public int revision() {
        return revision;
    }

    public String counterpartyBpn() {
        return counterpartyBpn;
    }

    public String requestedType() {
        return requestedType;
    }

    public List<String> requestedLocations() {
        return requestedLocations;
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
