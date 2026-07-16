package org.metaform.certo.provider.model;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jetbrains.annotations.NotNull;
import org.metaform.certo.common.Validations;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.persistence.StatusErrorListConverter;
import org.metaform.certo.common.web.ApiException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATION_REQUESTED;

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
 *
 * <p>Persisted via JPA with {@code @Version} optimistic locking; the §2.1.3 state-machine invariants that
 * a JVM monitor used to guard are now protected by the version check on save (a lost update fails). Because
 * JPA does not dirty-track in-place mutation of a converted collection, callers must {@code save} the
 * aggregate after mutating it.
 */
@Entity
@Table(name = "provider_certificate_exchange")
public class ProviderCertificateExchange {

    @Id
    @NotNull
    private String exchangeId;
    /**
     * The provider tenant (participant context) that owns this exchange. Never null — an exchange is always
     * opened within a resolved tenant (enforced in the constructor and by a NOT NULL column).
     */
    @NotNull
    @Column(nullable = false)
    private String participantContextId;
    @NotNull
    @Column(nullable = false)
    private String counterpartyBpn;
    /**
     * The counterparty consumer's DID — the token audience for outbound calls on this exchange.
     */
    @NotNull
    @Column(nullable = false)
    private String counterpartyDid;

    /** Unknown until the backend issues the certificate ({@link #fulfill}); null for a pending request. */
    private String certificateId;
    private int revision;

    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(nullable = false)
    private FulfillmentStatus fulfillmentStatus;
    @Convert(converter = StatusErrorListConverter.class)
    @Column(length = 65535)
    private List<StatusError> fulfillmentErrors;

    @Enumerated(EnumType.STRING)
    private AcceptanceStatus acceptanceStatus;
    @Convert(converter = StatusErrorListConverter.class)
    @Column(length = 65535)
    private List<StatusError> acceptanceErrors;

    private boolean consumerInitiated;

    /**
     * For a pending consumer request: what the consumer asked for, used to match a later issuance.
     */
    private String requestedType;
    // Normalized into child rows so the overlap/subset queries (queryRequests, fulfillableRequests) run in
    // the database. EAGER: small, and read by toPendingView after the query with open-in-view off.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exchange_requested_location", joinColumns = @JoinColumn(name = "exchange_id"))
    @Column(name = "location_bpn")
    private List<String> requestedLocations;
    private OffsetDateTime requestedAt;

    /**
     * Canonical key of a consumer-initiated request — its {@code certificateType} plus requested locations
     * (order-insensitive) — so the provider can reuse a still-live exchange for a repeated request rather than
     * opening a duplicate (CX-0135 &sect;2.1.1). Null for provider-initiated exchanges.
     */
    private String requestKey;

    @Version
    private long version;

    protected ProviderCertificateExchange() {
        // for JPA
    }

    public ProviderCertificateExchange(String exchangeId,
                                       String participantContextId,
                                       String certificateId,
                                       int revision,
                                       String counterpartyBpn,
                                       String counterpartyDid,
                                       FulfillmentStatus initialStatus) {
        this(exchangeId, participantContextId, certificateId, revision, counterpartyBpn, counterpartyDid, initialStatus, null);
    }

    public ProviderCertificateExchange(String exchangeId,
                                       String participantContextId,
                                       String certificateId,
                                       int revision,
                                       String counterpartyBpn,
                                       String counterpartyDid,
                                       FulfillmentStatus initialStatus,
                                       List<StatusError> initialErrors) {
        this.exchangeId = Validations.requireNonBlank(exchangeId, "exchangeId");
        this.participantContextId = Validations.requireNonBlank(participantContextId, "participantContextId");
        this.certificateId = certificateId;
        this.revision = revision;
        this.counterpartyBpn = Validations.requireNonBlank(counterpartyBpn, "counterpartyBpn");
        this.counterpartyDid = Validations.requireNonBlank(counterpartyDid, "counterpartyDid");
        this.fulfillmentStatus = Objects.requireNonNull(initialStatus, "fulfillmentStatus");
        this.fulfillmentErrors = copyOrNull(initialErrors);
    }

    /**
     * Opens a consumer-initiated exchange that is waiting for the backend to issue a certificate: no
     * certificate identity yet, status {@code CERTIFICATION_REQUESTED}, and the requested type/locations
     * retained so an issuance can be matched to it.
     */
    public static ProviderCertificateExchange pending(String exchangeId,
                                                      String participantContextId,
                                                      String counterpartyBpn,
                                                      String counterpartyDid,
                                                      String requestedType,
                                                      List<String> requestedLocations,
                                                      OffsetDateTime requestedAt) {
        var exchange = new ProviderCertificateExchange(exchangeId,
                participantContextId,
                null,
                0,
                counterpartyBpn,
                counterpartyDid,
                CERTIFICATION_REQUESTED);
        exchange.consumerInitiated = true;
        exchange.requestedType = requestedType;
        exchange.requestedLocations = copyOrNull(requestedLocations);
        exchange.requestedAt = requestedAt;
        return exchange;
    }

    /**
     * Advances the Fulfillment phase, rejecting illegal transitions and changes to a terminal state (409).
     */
    public void transitionFulfillment(FulfillmentStatus to, List<StatusError> errors) {
        if (!fulfillmentStatus.allowedNext().contains(to)) {
            throw ApiException.conflict("Illegal fulfillment transition " + fulfillmentStatus + " -> " + to
                                        + " for exchange " + exchangeId);
        }
        this.fulfillmentStatus = to;
        this.fulfillmentErrors = copyOrNull(errors);
    }

    /**
     * Binds the issued certificate identity and transitions the exchange to {@code FULFILLED} (the
     * backend produced the certificate). Illegal from a terminal state (409).
     */
    public void fulfill(String certificateId, int revision) {
        this.certificateId = Validations.requireNonBlank(certificateId, "certificateId");
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
        this.acceptanceErrors = copyOrNull(errors);
    }

    /**
     * Whether the consumer opened this exchange (so the provider may push fulfillment status to it).
     */
    public void markConsumerInitiated() {
        this.consumerInitiated = true;
    }

    public boolean isConsumerInitiated() {
        return consumerInitiated;
    }

    /** Records the canonical request key so a repeated consumer request can reuse this exchange while live. */
    public void assignRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String requestKey() {
        return requestKey;
    }

    /**
     * Whether this exchange is still live — neither the Fulfillment nor the Acceptance phase has reached a
     * terminal state — so a repeated request may reuse it (CX-0135 &sect;2.1.1: a re-attempt opens a new
     * exchange only after a terminal outcome).
     */
    public boolean isLive() {
        return !fulfillmentStatus.isTerminal() && (acceptanceStatus == null || !acceptanceStatus.isTerminal());
    }

    // --- accessors -----------------------------------------------------------------------------

    public String exchangeId() {
        return exchangeId;
    }

    /**
     * The provider tenant (participant context) that owns this exchange.
     */
    public String participantContextId() {
        return participantContextId;
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

    /**
     * The counterparty consumer's DID — the token audience for outbound calls on this exchange.
     */
    public String counterpartyDid() {
        return counterpartyDid;
    }

    public String requestedType() {
        return requestedType;
    }

    public List<String> requestedLocations() {
        return copyOrNull(requestedLocations);
    }

    public OffsetDateTime requestedAt() {
        return requestedAt;
    }

    public FulfillmentStatus fulfillmentStatus() {
        return fulfillmentStatus;
    }

    public List<StatusError> fulfillmentErrors() {
        return copyOrNull(fulfillmentErrors);
    }

    public AcceptanceStatus acceptanceStatus() {
        return acceptanceStatus;
    }

    public List<StatusError> acceptanceErrors() {
        return copyOrNull(acceptanceErrors);
    }

    /**
     * Snapshots a list to an immutable copy (preserving null) so stored state can't be mutated through a
     * shared reference on the way in or out.
     */
    private static <T> List<T> copyOrNull(List<T> list) {
        return list == null ? null : List.copyOf(list);
    }
}
