package org.metaform.certo.protocol.domain;

import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ProtocolVersion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The protocol binding of a Certificate Exchange: which wire-protocol version the counterparty speaks.
 * Recorded when an interaction originates over a non-native protocol (an inbound request) or when a caller
 * initiates one explicitly; the absence of a binding means the counterparty is native
 * ({@link ProtocolVersion#NATIVE}). This is the per-exchange attribute outbound routing keys on — keyed by
 * the {@code exchangeId}; {@code certificateId} + {@code peerDid} additionally resolve the binding for a
 * retrieval, which is keyed by certificate rather than exchange. The {@code peerDid} is the counterparty's
 * <b>verified</b> identity (the token subject).
 *
 * <p>Persisted via JPA. Recorded once and immutable thereafter; the {@code @Version} guards the record
 * like the other aggregates.
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
    private String peerDid;
    private String messageId;
    /** Optimistic-lock version (distinct from {@link #version} = the protocol version). */
    @Version
    private long lockVersion;

    protected ExchangeBinding() {
        // for JPA
    }

    public ExchangeBinding(String exchangeId, String certificateId, ProtocolVersion version,
                           CounterpartyRole role, String peerDid, String messageId) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.version = version;
        this.role = role;
        this.peerDid = peerDid;
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

    /** The counterparty's verified DID (token subject) — with {@link #certificateId} the retrieval-dispatch key. */
    public String peerDid() {
        return peerDid;
    }

    public String messageId() {
        return messageId;
    }
}
