package org.metaform.certo.protocol.domain;

import org.metaform.certo.protocol.CounterpartyRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The protocol binding of a Certificate Exchange: which wire protocol the counterparty speaks. Recorded
 * for <b>every</b> exchange when it is opened — by the adapter that received the inbound request (stamping
 * its own protocol id) or from the caller-stated protocol of an outbound initiate. There is no default: a
 * missing binding is a data-integrity error, not a fallback. This is the per-exchange attribute outbound
 * routing keys on, keyed by ({@code exchangeId}, {@code role}) — each party records its own view of the
 * exchange's counterparty. The {@code peerDid} is the counterparty's <b>verified</b> identity (the token
 * subject).
 *
 * <p>The {@code protocolId} is an open string (e.g. {@code "sce-1.0"}) — no core type enumerates
 * protocols. An adapter needing wire-specific per-exchange detail (e.g. a protocol-private receiver id)
 * adds an attributes column here when the need arises; none exists today.
 *
 * <p>Persisted via JPA. Recorded once and immutable thereafter; the {@code @Version} guards the record
 * like the other aggregates.
 */
@Entity
@Table(name = "exchange_binding")
@IdClass(ExchangeBindingId.class)
public class ExchangeBinding {

    @Id
    private String exchangeId;
    private String certificateId;
    @Column(name = "protocol_id")
    private String protocolId;
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_role")
    private CounterpartyRole role;
    private String peerDid;
    /** Optimistic-lock version. */
    @Version
    private long lockVersion;

    protected ExchangeBinding() {
        // for JPA
    }

    public ExchangeBinding(String exchangeId, String certificateId, String protocolId,
                           CounterpartyRole role, String peerDid) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.protocolId = protocolId;
        this.role = role;
        this.peerDid = peerDid;
    }

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    /** The wire protocol the counterparty speaks, as an open protocol id (e.g. {@code "sce-1.0"}). */
    public String protocolId() {
        return protocolId;
    }

    public CounterpartyRole role() {
        return role;
    }

    /** The counterparty's verified DID (token subject). */
    public String peerDid() {
        return peerDid;
    }

}
