package org.metaform.certo.protocol;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The protocol binding of a Certificate Exchange: which wire-protocol version the counterparty speaks and
 * how to reach it. Recorded when an interaction originates over a non-native protocol (an inbound
 * request) or when a caller initiates one explicitly; the absence of a binding means the counterparty is
 * native v3 ({@link ProtocolVersion#NATIVE}). This is the per-exchange attribute outbound routing keys
 * on — keyed by the {@code exchangeId}; {@code certificateId} + {@code peerBpn} additionally correlate a
 * v2.4.0 {@code documentId} back to the exchange for inbound status.
 *
 * <p>Persisted via JPA. Insert-only (recorded once, never mutated), so it carries no {@code @Version}.
 */
@Entity
@Table(name = "exchange_binding")
public class ExchangeBinding {

    @Id
    private String exchangeId;
    private String certificateId;
    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_version")
    private ProtocolVersion version;
    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_role")
    private CounterpartyRole role;
    private String peerBpn;
    private String messageId;

    protected ExchangeBinding() {
        // for JPA
    }

    public ExchangeBinding(String exchangeId, String certificateId, ProtocolVersion version,
                           CounterpartyRole role, String peerBpn, String messageId) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.version = version;
        this.role = role;
        this.peerBpn = peerBpn;
        this.messageId = messageId;
    }

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    public ProtocolVersion version() {
        return version;
    }

    public CounterpartyRole role() {
        return role;
    }

    public String peerBpn() {
        return peerBpn;
    }

    public String messageId() {
        return messageId;
    }
}
