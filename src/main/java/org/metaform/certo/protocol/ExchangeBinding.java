package org.metaform.certo.protocol;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The protocol binding of a Certificate Exchange: which wire-protocol version the counterparty speaks and
 * how to reach it. Recorded when an interaction originates over a non-native protocol (an inbound
 * request) or when a caller initiates one explicitly; the absence of a binding means the counterparty is
 * native v3 ({@link ProtocolVersion#NATIVE}). This is the per-exchange attribute outbound routing keys
 * on — keyed by the {@code exchangeId}; {@code certificateId} + {@code peerDid} additionally correlate a
 * v2.4.0 {@code documentId} back to the exchange for inbound status. The {@code peerDid} is the counterparty's
 * <b>verified</b> identity (the token subject), used for that inbound correlation; {@code peerBpn} is retained
 * only as the v2.4.0 wire receiver for outbound messages.
 *
 * <p>Persisted via JPA. Recorded once and mutated at most once — {@link #assignCertificateId} backfills the
 * {@code certificateId} when a pending consumer-initiated request is later fulfilled — so it carries a
 * {@code @Version} like the other mutable aggregates.
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
    private String peerDid;
    private String messageId;
    /** Optimistic-lock version (distinct from {@link #version} = the protocol version). */
    @Version
    private long lockVersion;

    protected ExchangeBinding() {
        // for JPA
    }

    public ExchangeBinding(String exchangeId, String certificateId, ProtocolVersion version,
                           CounterpartyRole role, String peerBpn, String peerDid, String messageId) {
        this.exchangeId = exchangeId;
        this.certificateId = certificateId;
        this.version = version;
        this.role = role;
        this.peerBpn = peerBpn;
        this.peerDid = peerDid;
        this.messageId = messageId;
    }

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    /**
     * Backfills the {@code certificateId} once the provider issues it (a consumer-initiated request is bound
     * while still pending, before the certificate exists), so an inbound v2.4.0 {@code /status} — which keys
     * on the certificateId — correlates back to this exchange.
     */
    public void assignCertificateId(String certificateId) {
        this.certificateId = certificateId;
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

    /** The counterparty's verified DID (token subject) — the key for inbound v2.4.0 status correlation. */
    public String peerDid() {
        return peerDid;
    }

    public String messageId() {
        return messageId;
    }
}
