package org.metaform.certo.provider.domain;

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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.jetbrains.annotations.NotNull;
import org.metaform.certo.common.event.CertificateExchangeStatusChanged;
import org.metaform.certo.common.event.ExchangeEventType;
import org.metaform.certo.common.event.ExchangePhase;
import org.metaform.certo.common.event.ExchangeRole;
import org.metaform.certo.common.util.Validations;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.persistence.StatusErrorListConverter;
import org.metaform.certo.common.web.ApiException;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.metaform.certo.common.model.FulfillmentStatus.PENDING;

/**
 * The provider's record of a {@code Certificate Exchange} — one end-to-end
 * delivery interaction, correlated by {@code exchangeId}.
 *
 * <p>Enforces the the SCE specification state machine: Fulfillment transitions must be legal, terminal
 * states are immutable, and Acceptance may only be recorded once the exchange is {@code FULFILLED}.
 *
 * <p>A consumer-initiated request for a certificate the provider does not yet hold is opened as a
 * {@link #pending} exchange in {@code PENDING} — awaiting a certification-authority
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
@Table(name = "provider_certificate_exchange",
        // At most one exchange per (tenant, counterparty, messageId) — so two concurrent opens repeating the
        // same messageId cannot both create a row (the loser's insert is rejected by the DB and its
        // transaction rolls back; the client retry then finds the winner). The constraint is never released:
        // a repeat of the pair returns the same exchange for as long as it is retained, and a genuinely new
        // request carries a new messageId (SCE exchange-creation idempotency).
        uniqueConstraints = @UniqueConstraint(name = "uk_exchange_message",
                columnNames = {"participant_context_id", "counterparty_id", "message_id"}))
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
    /**
     * The counterparty consumer's identity (its DID) — the token audience for outbound calls on this
     * exchange and the key of every counterparty guard.
     */
    @NotNull
    @Column(nullable = false)
    private String counterpartyId;

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

    /**
     * For a pending consumer request: what the consumer asked for, used to match a later issuance.
     */
    private String requestedType;
    // Normalized into child rows (one per location filter, three identifier columns) so the backlog
    // queries run in the database. EAGER: small, and read by toPendingView after the query with
    // open-in-view off.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "exchange_requested_location", joinColumns = @JoinColumn(name = "exchange_id"))
    private List<LocationFilter> requestedLocations;
    private OffsetDateTime requestedAt;

    /**
     * The {@code messageId} of the certificate request that opened this exchange — the key that makes
     * creation idempotent: a retried request repeats the (counterparty, {@code messageId}) pair and gets
     * this exchange back; a genuinely new request carries a new one. Never released — a repeat after a
     * terminal outcome still resolves to this exchange.
     */
    private String messageId;

    @Version
    private long version;

    /**
     * Status changes recorded since this aggregate was last saved, drained by Spring Data's
     * {@code @DomainEvents} support on {@code save()} (see {@link #domainEvents()}). Transient in every
     * sense: never persisted, and never populated on an entity loaded from the database — only a
     * mutation made in this JVM records one.
     */
    @Transient
    private final transient List<Object> domainEvents = new ArrayList<>();

    protected ProviderCertificateExchange() {
        // for JPA
    }

    public ProviderCertificateExchange(String exchangeId,
                                       String participantContextId,
                                       String certificateId,
                                       int revision,
                                       String counterpartyId,
                                       FulfillmentStatus initialStatus) {
        this(exchangeId, participantContextId, certificateId, revision, counterpartyId, initialStatus, null);
    }

    public ProviderCertificateExchange(String exchangeId,
                                       String participantContextId,
                                       String certificateId,
                                       int revision,
                                       String counterpartyId,
                                       FulfillmentStatus initialStatus,
                                       List<StatusError> initialErrors) {
        this.exchangeId = Validations.requireNonBlank(exchangeId, "exchangeId");
        this.participantContextId = Validations.requireNonBlank(participantContextId, "participantContextId");
        this.certificateId = certificateId;
        this.revision = revision;
        this.counterpartyId = Validations.requireNonBlank(counterpartyId, "counterpartyId");
        this.fulfillmentStatus = Objects.requireNonNull(initialStatus, "fulfillmentStatus");
        this.fulfillmentErrors = copyOrNull(initialErrors);
        recordFulfillmentChange(null);
    }

    /**
     * Opens a consumer-initiated exchange that is waiting for the backend to issue a certificate: no
     * certificate identity yet, status {@code PENDING}, and the requested type/locations
     * retained so an issuance can be matched to it.
     */
    public static ProviderCertificateExchange pending(String exchangeId,
                                                      String participantContextId,
                                                      String counterpartyId,
                                                      String requestedType,
                                                      List<LocationFilter> requestedLocations,
                                                      OffsetDateTime requestedAt) {
        var exchange = new ProviderCertificateExchange(exchangeId,
                participantContextId,
                null,
                0,
                counterpartyId,
                PENDING);
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
        var previous = fulfillmentStatus;
        this.fulfillmentStatus = to;
        this.fulfillmentErrors = copyOrNull(errors);
        recordFulfillmentChange(previous);
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
     * Records an Acceptance-phase outcome. Feedback may follow any Fulfillment
     * <em>outcome</em> — {@code FULFILLED} or {@code DECLINED} (the state machine draws Feedback
     * from all three) — but not an exchange still in progress with no outcome yet. Transitions out of a
     * terminal acceptance state are rejected (409), except a <b>re-report of the current state</b>, which is
     * treated as a duplicate and ignored without a transition (SCE acceptance-notification rules).
     */
    public void recordAcceptance(AcceptanceStatus to, List<StatusError> errors) {
        // Acceptance begins only once Fulfillment has handed the certificate off (FULFILLED → {ACCEPTED,
        // REJECTED}); earlier states have delivered nothing to judge and DECLINED concludes the exchange.
        if (fulfillmentStatus != FulfillmentStatus.FULFILLED) {
            throw ApiException.conflict("Exchange " + exchangeId + " is not FULFILLED"
                                        + " (current fulfillment status: " + fulfillmentStatus + ")");
        }
        if (to == acceptanceStatus) {
            return; // a re-report of the current state is a duplicate, not a transition
        }
        if (acceptanceStatus != null && !acceptanceStatus.allowedNext().contains(to)) {
            throw ApiException.conflict("Illegal acceptance transition " + acceptanceStatus + " -> " + to
                                        + " for exchange " + exchangeId);
        }
        var previous = acceptanceStatus;
        this.acceptanceStatus = to;
        this.acceptanceErrors = copyOrNull(errors);
        recordAcceptanceChange(previous);
    }

    /** Records the opening request's {@code messageId} so a repeated request resolves to this exchange. */
    public void assignMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String messageId() {
        return messageId;
    }

    /**
     * Whether this exchange is still live — neither the Fulfillment nor the Acceptance phase has reached a
     * terminal state — so a repeated request may reuse it.
     */
    public boolean isLive() {
        return !fulfillmentStatus.isTerminal() && (acceptanceStatus == null || !acceptanceStatus.isTerminal());
    }

    // --- domain events -------------------------------------------------------------------------

    /**
     * Records the Fulfillment status now in effect. {@code previous} is null when the exchange was just
     * opened, which is how a consumer tells "opened in state X" from "transitioned to X".
     */
    private void recordFulfillmentChange(FulfillmentStatus previous) {
        domainEvents.add(new CertificateExchangeStatusChanged(
                ExchangeRole.PROVIDER,
                ExchangePhase.FULFILLMENT,
                ExchangeEventType.of(fulfillmentStatus),
                exchangeId,
                participantContextId,
                counterpartyId,
                certificateId,
                certificateId == null ? null : revision,
                previous == null ? null : previous.name(),
                fulfillmentStatus.name(),
                fulfillmentErrors,
                OffsetDateTime.now()));
    }

    /** Records the Acceptance status now in effect; {@code previous} is null for the first verdict recorded. */
    private void recordAcceptanceChange(AcceptanceStatus previous) {
        domainEvents.add(new CertificateExchangeStatusChanged(
                ExchangeRole.PROVIDER,
                ExchangePhase.ACCEPTANCE,
                ExchangeEventType.of(acceptanceStatus),
                exchangeId,
                participantContextId,
                counterpartyId,
                certificateId,
                certificateId == null ? null : revision,
                previous == null ? null : previous.name(),
                acceptanceStatus.name(),
                acceptanceErrors,
                OffsetDateTime.now()));
    }

    /**
     * Drained by Spring Data on {@code save()} and handed to the {@code ApplicationEventPublisher}. The
     * NATS publisher listens after commit, so a transaction that rolls back announces nothing.
     */
    @DomainEvents
    Collection<Object> domainEvents() {
        return List.copyOf(domainEvents);
    }

    /** Clears the recorded events after publication so a second {@code save()} does not re-publish them. */
    @AfterDomainEventPublication
    void clearDomainEvents() {
        domainEvents.clear();
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

    public String counterpartyId() {
        return counterpartyId;
    }

    public String requestedType() {
        return requestedType;
    }

    public List<LocationFilter> requestedLocations() {
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
