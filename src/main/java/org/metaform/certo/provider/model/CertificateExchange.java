package org.metaform.certo.provider.model;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The provider's record of a {@code Certificate Exchange} (CX-0135 &sect;2.1) — one end-to-end
 * delivery interaction for a specific (certificateId, version), correlated by {@code exchangeId}.
 *
 * <p>Holds the Fulfillment-phase state (provider-owned) and, once the consumer reports it via the
 * acceptance-notification endpoint, the Acceptance-phase state (consumer-owned).
 */
public class CertificateExchange {

    private final String exchangeId;
    private final String certificateId;
    private final int version;
    private final String counterpartyBpn;

    private FulfillmentStatus fulfillmentStatus;
    private List<StatusError> fulfillmentErrors;

    private AcceptanceStatus acceptanceStatus;
    private List<StatusError> acceptanceErrors;

    public CertificateExchange(String exchangeId, String certificateId, int version, String counterpartyBpn,
                               FulfillmentStatus fulfillmentStatus) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.version = version;
        this.counterpartyBpn = counterpartyBpn;
        this.fulfillmentStatus = fulfillmentStatus;
    }

    public void setFulfillment(FulfillmentStatus status, List<StatusError> errors) {
        this.fulfillmentStatus = status;
        this.fulfillmentErrors = errors;
    }

    public void setAcceptance(AcceptanceStatus status, List<StatusError> errors) {
        this.acceptanceStatus = status;
        this.acceptanceErrors = errors;
    }

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
