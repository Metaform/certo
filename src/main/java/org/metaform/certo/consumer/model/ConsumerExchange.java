package org.metaform.certo.consumer.model;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * The consumer's view of a {@code Certificate Exchange} it is party to (CX-0135 &sect;2.1). Holds the
 * Acceptance-phase decision the consumer is authoritative for and that a provider may query via
 * {@code GET /certificate-acceptance-status/{id}} (CX-0135 &sect;4.3.3).
 */
public class ConsumerExchange {

    private final String exchangeId;
    private final String certificateId;
    private AcceptanceStatus acceptanceStatus;
    private List<StatusError> acceptanceErrors;

    public ConsumerExchange(String exchangeId, String certificateId, AcceptanceStatus acceptanceStatus,
                            List<StatusError> acceptanceErrors) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.acceptanceStatus = acceptanceStatus;
        this.acceptanceErrors = acceptanceErrors;
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

    public AcceptanceStatus acceptanceStatus() {
        return acceptanceStatus;
    }

    public List<StatusError> acceptanceErrors() {
        return acceptanceErrors;
    }
}
