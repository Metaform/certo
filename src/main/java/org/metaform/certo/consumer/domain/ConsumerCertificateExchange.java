package org.metaform.certo.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.jetbrains.annotations.NotNull;
import org.metaform.certo.common.event.CertificateExchangeStatusChanged;
import org.metaform.certo.common.event.ExchangeEventType;
import org.metaform.certo.common.event.ExchangePhase;
import org.metaform.certo.common.event.ExchangeRole;
import org.metaform.certo.common.util.Validations;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.persistence.RetrievedCertificateConverter;
import org.metaform.certo.common.persistence.StatusErrorListConverter;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The consumer's record of a {@code Certificate Exchange} it is party to (CX-0135 &sect;2.1), holding
 * both phases — like the provider's {@code ProviderCertificateExchange}. The consumer <em>mirrors</em> the
 * provider-owned Fulfillment status (tracked while it waits on a consumer-initiated request) and is
 * <em>authoritative</em> for the Acceptance status it reports.
 *
 * <p>{@code consumerInitiated} distinguishes the two ways an exchange is opened: a consumer request
 * (pull) versus a provider lifecycle {@code CREATED} notification (push, which enters directly at
 * {@code FULFILLED}). The Acceptance state machine is enforced; terminal states are immutable.
 *
 * <p>Persisted via JPA with {@code @Version} optimistic locking (a concurrent poll/accept/push that would
 * lose an update fails the version check instead of being serialized by a JVM monitor). Because JPA does
 * not dirty-track in-place mutation of a converted collection, callers must {@code save} the aggregate
 * after mutating it.
 */
@Entity
@Table(name = "consumer_certificate_exchange")
public class ConsumerCertificateExchange {

    @Id
    @NotNull
    private String exchangeId;
    /** Unknown until the provider reports one; null for a request still awaiting fulfillment. */
    private String certificateId;
    private Integer revision;
    private boolean consumerInitiated;

    /** The consumer's own tenant (participant context) this exchange belongs to. */
    @NotNull
    @Column(nullable = false)
    private String participantContextId;
    /** The counterparty provider's BPN — the target of the consumer's outbound calls for this exchange. */
    @NotNull
    @Column(nullable = false)
    private String providerBpn;
    /** The counterparty provider's DID — the token audience for those outbound calls. */
    @NotNull
    @Column(nullable = false)
    private String providerDid;

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
     * Whether the recorded acceptance has been successfully reported to the provider. Recording is durable but
     * the outbound report is best-effort (post-commit), so a crash in between leaves {@code false}; the
     * reconciliation query ({@code queryExchanges}) surfaces such exchanges so delivery can be re-driven. The
     * re-report is idempotent on the provider (its acceptance CloudEvent {@code id} is stable per exchange).
     */
    private boolean acceptanceReported;

    /**
     * The certificate content when it arrived <b>inline</b> (an embedded v2.4.0 push), retained so a later
     * management-driven {@code retrieve}/{@code accept} can evaluate it — there is no pull endpoint to
     * re-fetch it from. Null for by-reference exchanges, which are (re-)fetched from the provider on demand.
     */
    // Larger than the other JSON columns: this holds the inline certificate plus its base64 document
    // binaries. 10 MB stays within Postgres's varchar limit while comfortably fitting certificate PDFs.
    @Convert(converter = RetrievedCertificateConverter.class)
    @Column(length = 10_000_000)
    private RetrievedCertificate embeddedContent;

    @Version
    private long version;

    /**
     * Status changes recorded since this aggregate was last saved, drained by Spring Data's
     * {@code @DomainEvents} support on {@code save()}. Never persisted, and never populated on an
     * entity loaded from the database — only a mutation made in this JVM records one.
     */
    @Transient
    private final transient List<Object> domainEvents = new ArrayList<>();

    protected ConsumerCertificateExchange() {
        // for JPA
    }

    public ConsumerCertificateExchange(String exchangeId, String certificateId, Integer revision, boolean consumerInitiated,
                            FulfillmentStatus fulfillmentStatus, List<StatusError> fulfillmentErrors,
                            String participantContextId, String providerBpn, String providerDid) {
        this.exchangeId = Validations.requireNonBlank(exchangeId, "exchangeId");
        this.certificateId = certificateId;
        this.revision = revision;
        this.consumerInitiated = consumerInitiated;
        this.fulfillmentStatus = Objects.requireNonNull(fulfillmentStatus, "fulfillmentStatus");
        this.fulfillmentErrors = fulfillmentErrors;
        this.participantContextId = Validations.requireNonBlank(participantContextId, "participantContextId");
        this.providerBpn = Validations.requireNonBlank(providerBpn, "providerBpn");
        this.providerDid = Validations.requireNonBlank(providerDid, "providerDid");
        recordFulfillmentChange(null);
    }

    /**
     * Mirrors a provider-reported Fulfillment status (from a poll or a pushed notification). The
     * {@code certificateId} may be unknown when a request is first opened (the provider assigns it only
     * once the backend issues the certificate); it is adopted here when the provider reports one.
     */
    public void updateFulfillment(FulfillmentStatus status, String certificateId, List<StatusError> errors) {
        var previous = fulfillmentStatus;
        this.fulfillmentStatus = status;
        this.fulfillmentErrors = errors;
        if (certificateId != null) {
            this.certificateId = certificateId;
        }
        // Only a real change is an event. Unlike the provider's transitionFulfillment (which rejects a
        // no-op transition outright), this method mirrors whatever the provider last reported and is
        // called on every poll — most of which report the status unchanged.
        if (previous != status) {
            recordFulfillmentChange(previous);
        }
    }

    /**
     * Advances the Acceptance phase, rejecting illegal or post-terminal transitions (409). Reporting
     * {@code RETRIEVED} is optional, so the first status may be a terminal verdict reached directly from
     * {@code FULFILLED} (CX-0135 &sect;2.1.3).
     */
    public void transitionAcceptance(AcceptanceStatus status, List<StatusError> errors) {
        if (acceptanceStatus != null && !acceptanceStatus.allowedNext().contains(status)) {
            throw ApiException.conflict("Illegal acceptance transition " + acceptanceStatus + " -> " + status
                    + " for exchange " + exchangeId);
        }
        var previous = acceptanceStatus;
        this.acceptanceStatus = status;
        this.acceptanceErrors = errors;
        this.acceptanceReported = false;
        recordAcceptanceChange(previous);
    }

    // --- domain events -------------------------------------------------------------------------

    /**
     * Records the Fulfillment status now in effect (mirrored from the provider). {@code previous} is
     * null when the exchange was just opened.
     */
    private void recordFulfillmentChange(FulfillmentStatus previous) {
        domainEvents.add(new CertificateExchangeStatusChanged(
                ExchangeRole.CONSUMER,
                ExchangePhase.FULFILLMENT,
                ExchangeEventType.of(fulfillmentStatus),
                exchangeId,
                participantContextId,
                providerBpn,
                providerDid,
                certificateId,
                certificateId == null ? null : revision,
                previous == null ? null : previous.name(),
                fulfillmentStatus.name(),
                fulfillmentErrors,
                OffsetDateTime.now()));
    }

    /** Records the Acceptance verdict now in effect; {@code previous} is null for the first one recorded. */
    private void recordAcceptanceChange(AcceptanceStatus previous) {
        domainEvents.add(new CertificateExchangeStatusChanged(
                ExchangeRole.CONSUMER,
                ExchangePhase.ACCEPTANCE,
                ExchangeEventType.of(acceptanceStatus),
                exchangeId,
                participantContextId,
                providerBpn,
                providerDid,
                certificateId,
                certificateId == null ? null : revision,
                previous == null ? null : previous.name(),
                acceptanceStatus.name(),
                acceptanceErrors,
                OffsetDateTime.now()));
    }

    /** Drained by Spring Data on {@code save()}; the NATS publisher listens after commit. */
    @DomainEvents
    Collection<Object> domainEvents() {
        return List.copyOf(domainEvents);
    }

    /** Clears the recorded events after publication so a second {@code save()} does not re-publish them. */
    @AfterDomainEventPublication
    void clearDomainEvents() {
        domainEvents.clear();
    }

    /** Whether this exchange's recorded acceptance still needs (re-)reporting to the provider. */
    public boolean acceptanceReportPending() {
        return acceptanceStatus != null && !acceptanceReported;
    }

    public void markAcceptanceReported() {
        this.acceptanceReported = true;
    }

    /** Retains inline certificate content (an embedded push) for a later management-driven retrieve/accept. */
    public void attachEmbeddedContent(RetrievedCertificate content) {
        this.embeddedContent = content;
    }

    public RetrievedCertificate embeddedContent() {
        return embeddedContent;
    }

    public String exchangeId() {
        return exchangeId;
    }

    public String certificateId() {
        return certificateId;
    }

    public Integer revision() {
        return revision;
    }

    public boolean consumerInitiated() {
        return consumerInitiated;
    }

    public String participantContextId() {
        return participantContextId;
    }

    public String providerBpn() {
        return providerBpn;
    }

    /** The counterparty provider's DID — the token audience for the consumer's outbound calls. */
    public String providerDid() {
        return providerDid;
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
