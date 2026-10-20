package org.metaform.certo.provider;

import org.metaform.certo.common.cloudevent.EventBatch;
import org.metaform.certo.common.cloudevent.store.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertificateTypes;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.metaform.certo.provider.dto.LifecycleNotification;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.dto.CertificateRequestPage;
import org.metaform.certo.provider.dto.CertificateRequestQuery;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.dto.ExchangeView;
import org.metaform.certo.provider.dto.PendingRequestView;
import org.metaform.certo.provider.dto.NotifyRequest;
import org.metaform.certo.provider.domain.Certificate;
import org.metaform.certo.provider.domain.CertificateRevision;
import org.metaform.certo.provider.domain.ProviderCertificateExchange;
import org.metaform.certo.provider.spi.AcceptancePoller;
import org.metaform.certo.provider.spi.InboundAcceptanceEvent;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.metaform.certo.provider.store.ExchangeSpecifications;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.UUID.randomUUID;
import static org.metaform.certo.common.util.TransactionSupport.afterCommit;
import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATE_REQUESTED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;
import static org.metaform.certo.common.web.ApiException.badRequest;
import static org.metaform.certo.common.web.ApiException.requireText;

/**
 * The provider's <b>certificate exchange</b> lifecycle: opening consumer-initiated
 * requests, the backend fulfill/fail/decline outcomes and the fulfillment-status push, recording inbound
 * acceptance feedback, the request-backlog queries, and the single-target lifecycle notify. Consults
 * {@link ProviderCatalogService} for the held certificate an exchange is fulfilled with.
 */
@Service
@Transactional
public class ProviderExchangeService {


    private final ProviderCertificateExchangeStore exchangeStore;
    private final ExchangeBindingStore bindingStore;
    private final ProcessedEventStore eventStore;
    private final ParticipantContextStore contextStore;
    private final ConsumerNotifier consumerNotifier;
    private final AcceptancePoller acceptancePoller;
    private final ProviderCatalogService catalog;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProviderExchangeService(ProviderCertificateExchangeStore exchangeStore,
                                   ExchangeBindingStore bindingStore,
                                   ProcessedEventStore eventStore,
                                   ParticipantContextStore contextStore,
                                   ConsumerNotifier consumerNotifier,
                                   AcceptancePoller acceptancePoller,
                                   ProviderCatalogService catalog,
                                   PlatformTransactionManager txManager,
                                   Clock clock) {
        this.exchangeStore = exchangeStore;
        this.bindingStore = bindingStore;
        this.eventStore = eventStore;
        this.contextStore = contextStore;
        this.consumerNotifier = consumerNotifier;
        this.acceptancePoller = acceptancePoller;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange}. A held certificate
     * covering the requested locations &rarr; immediate {@code FULFILLED}; otherwise
     * {@code CERTIFICATE_REQUESTED} — the exchange waits for the backend to {@code addCertificate} the
     * certificate (then {@code fulfill}) or {@link #declineRequest decline} it.
     *
     * <p>Idempotent on (counterparty, {@code messageId}): a retried request repeats the pair and gets the
     * already-created exchange back — whatever state it is in, terminal included. A genuinely new request
     * (a re-attempt after a terminal outcome, say) carries a new {@code messageId}.
     */
    public ExchangeView requestCertificate(CertificateRequest request, VerifiedRequestContext requestContext,
                                           String protocolId) {
        requireText(request.certificateType(), "A certificate request must specify a certificateType");
        if (!CertificateTypes.isValid(request.certificateType())) {
            throw badRequest("certificateType must be an opaque code of lowercase letters and digits only: " + request.certificateType());
        }
        // The addressed provider tenant (the verified audience) owns the exchange; the verified caller is the
        // requesting consumer (the counterparty). The verifier already resolved this participant context from
        // the token audience, so it is non-null and known to exist — no re-check needed.
        var contextId = requestContext.participantContextId();
        // The counterparty's identity is the verified token subject (its DID) — never resolved later.
        var counterpartyId = requestContext.subject();
        var requestedLocations = request.certifiedLocations();
        requireUuidMessageId(request.messageId());
        if (requestedLocations.stream().anyMatch(f -> f == null || !f.specifiesAnything())) {
            throw badRequest("Each certifiedLocations entry must supply at least one identifier property");
        }

        // Idempotent open: a request repeating this counterparty's (verified DID) messageId is the same
        // message, so the already-created exchange is returned — whatever state it is in. The DID scoping
        // means this only ever collapses one consumer's own repeats.
        var existing = exchangeStore
                .findByParticipantContextIdAndCounterpartyIdAndMessageId(contextId, counterpartyId, request.messageId());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // No live exchange: a held certificate covering the request fulfills immediately, otherwise the request
        // is submitted for certification (pending). A truly concurrent duplicate open loses the unique
        // constraint here; its transaction rolls back and the client retry then finds the winner above.
        var held = catalog.findCertificateForLocations(contextId, request.certificateType(), requestedLocations);
        if (held.isPresent()) {
            var certificate = held.get();
            var exchange = new ProviderCertificateExchange(randomUUID().toString(),
                    contextId,
                    certificate.certificateId(),
                    certificate.latestRevision().revision(),
                    counterpartyId,
                    FULFILLED);
            exchange.assignMessageId(request.messageId());
            exchangeStore.save(exchange);
            recordBinding(exchange.exchangeId(), certificate.certificateId(), protocolId, counterpartyId);
            return toResponse(exchange);
        }

        var exchange = ProviderCertificateExchange.pending(randomUUID().toString(),
                contextId,
                counterpartyId,
                request.certificateType(),
                requestedLocations,
                OffsetDateTime.now(clock));
        exchange.assignMessageId(request.messageId());
        exchangeStore.save(exchange);
        recordBinding(exchange.exchangeId(), null, protocolId, counterpartyId);
        return toResponse(exchange);
    }

    /** Stamps the opened exchange with the protocol the receiving adapter speaks (every exchange is bound). */
    private void recordBinding(String exchangeId, String certificateId, String protocolId, String counterpartyId) {
        bindingStore.record(new ExchangeBinding(exchangeId, certificateId, protocolId,
                CounterpartyRole.CONSUMER, counterpartyId));
    }

    /** Renders an exchange as its full two-phase view. */
    private static ExchangeView toResponse(ProviderCertificateExchange exchange) {
        return new ExchangeView(exchange.exchangeId(), exchange.certificateId(), exchange.revision(),
                exchange.fulfillmentStatus(), exchange.fulfillmentErrors(),
                exchange.acceptanceStatus(), exchange.acceptanceErrors());
    }

    /**
     * Backend outcome — the provider declines the request (a business decision, distinct from a technical
     * failure): a waiting exchange &rarr; {@code DECLINED}, pushing the terminal status over {@code flowId}.
     */
    public CertificateRequestStatus declineRequest(String contextId, String exchangeId, String flowId, String reason) {
        requireFlow(flowId);
        var exchange = requireExchange(contextId, exchangeId);
        var message = (reason == null || reason.isBlank()) ? "The provider declined the request" : reason;
        exchange.transitionFulfillment(DECLINED, List.of(new StatusError(message)));
        exchangeStore.save(exchange);
        afterCommit(() -> pushFulfillmentStatus(exchange, flowId));
        return toRequestStatus(exchange);
    }

    /**
     * Backend outcome — fulfills <b>one</b> waiting consumer-initiated exchange with a now-held certificate
     * that covers it ({@code CERTIFICATE_REQUESTED → FULFILLED}) and pushes {@code FULFILLED} over
     * {@code flowId}. Rejected (409) if the exchange is not awaiting fulfillment, or no held certificate
     * covers the request yet.
     */
    public CertificateRequestStatus fulfill(String contextId, String exchangeId, String flowId) {
        requireFlow(flowId);
        var exchange = requireExchange(contextId, exchangeId);
        if (exchange.fulfillmentStatus() != CERTIFICATE_REQUESTED) {
            throw ApiException.conflict("Exchange " + exchangeId + " is not awaiting fulfillment"
                                        + " (current fulfillment status: " + exchange.fulfillmentStatus() + ")");
        }
        var certificate = catalog.findCertificateForLocations(exchange.participantContextId(), exchange.requestedType(),
                        exchange.requestedLocations() == null ? List.of() : exchange.requestedLocations())
                .orElseThrow(() -> ApiException.conflict(
                        "No held certificate covers the request for exchange " + exchangeId + " yet"));
        exchange.fulfill(certificate.certificateId(), certificate.latestRevision().revision());
        exchangeStore.save(exchange);
        afterCommit(() -> pushFulfillmentStatus(exchange, flowId));
        return toRequestStatus(exchange);
    }

    /**
     * UC1 — request-centric query: the consumer-initiated exchanges matching the filter (by default those
     * still {@code CERTIFICATE_REQUESTED}), optionally narrowed by {@code certificateType} and
     * {@code certifiedLocations}. Not part of the SCE specification.
     */
    @Transactional(readOnly = true)
    public CertificateRequestPage queryRequests(String contextId, CertificateRequestQuery query) {
        requireContextExists(contextId);
        var status = (query != null && query.status() != null) ? query.status() : CERTIFICATE_REQUESTED;
        var type = query == null ? null : query.certificateType();
        var locations = (query == null || query.certifiedLocations() == null)
                ? List.<String>of() : query.certifiedLocations();
        var spec = ExchangeSpecifications.pendingMatching(contextId, status, type, locations);
        var items = exchangeStore.findAll(spec, Sort.by("exchangeId")).stream()
                .map(ProviderExchangeService::toPendingView)
                .toList();
        return new CertificateRequestPage(items);
    }

    /**
     * UC2 — certificate-centric query: the waiting exchanges a specific issued certificate covers (its type
     * and certified locations). The primary driver of the issue&rarr;notify loop. Not part of the SCE specification.
     */
    @Transactional(readOnly = true)
    public CertificateRequestPage fulfillableRequests(String contextId, String certificateId) {
        var certificate = catalog.resolveCertificate(contextId, certificateId);
        // Pre-filter in the database on tenant/status/type, then apply the location-filter coverage rule
        // (every requested filter matched by some certified location) in memory — the waiting backlog per
        // tenant and type is small, and the AND-per-filter match does not reduce to a column predicate.
        var spec = ExchangeSpecifications.pendingMatching(contextId, CERTIFICATE_REQUESTED,
                certificate.certificateType(), List.of());
        var items = exchangeStore.findAll(spec, Sort.by("exchangeId")).stream()
                .filter(exchange -> LocationFilter.coveredBy(exchange.requestedLocations(), certificate.certifiedLocations()))
                .map(ProviderExchangeService::toPendingView)
                .toList();
        return new CertificateRequestPage(items);
    }

    /** Returns the provider's full view of an exchange — both phases (management/inspection; not in the SCE specification). */
    @Transactional(readOnly = true)
    public ExchangeView getExchangeView(String contextId, String exchangeId) {
        var exchange = requireExchange(contextId, exchangeId);
        return new ExchangeView(exchange.exchangeId(), exchange.certificateId(), exchange.revision(),
                exchange.fulfillmentStatus(), exchange.fulfillmentErrors(),
                exchange.acceptanceStatus(), exchange.acceptanceErrors());
    }

    /**
     * Provider-initiated recovery of a consumer's acceptance decision: GETs the
     * consumer's acceptance-status over a fresh {@code flowId} and records any verdict on the exchange — the
     * pull fallback for a lost best-effort acceptance push, avoiding a durable outbox. Only a consumer
     * speaking the poller's protocol exposes this endpoint; any other binding is rejected. Idempotent:
     * re-recording the same verdict is a no-op.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExchangeView pollAcceptance(String contextId, String exchangeId, String flowId) {
        requireFlow(flowId);
        var exchange = requireExchange(contextId, exchangeId);
        // Polling applies only to a consumer whose protocol the poller speaks; any other binding means the
        // counterparty does not expose the acceptance-status pull.
        if (!isPollable(exchangeId)) {
            throw ApiException.conflict("Cannot poll acceptance: the exchange's protocol has no acceptance-status pull");
        }
        var call = new OutboundCall(requireContext(contextId), exchange.counterpartyId(), flowId);
        AcceptanceStatusData polled;
        try {
            polled = acceptancePoller.pollAcceptance(exchangeId, call).orElse(null);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Acceptance poll failed: " + e.getMessage());
        }
        if (polled != null && polled.status() != null) {
            var status = polled.status();
            var errors = polled.errors();
            // Record the verdict pulled from the authoritative consumer endpoint (no forgery risk — the
            // provider polled its own exchange's known consumer) in its own transaction; skip if already set.
            tx.executeWithoutResult(_ -> {
                var current = requireExchange(contextId, exchangeId);
                if (current.acceptanceStatus() != status) {
                    current.recordAcceptance(status, errors);
                    exchangeStore.save(current);
                }
            });
        }
        return getExchangeView(contextId, exchangeId);
    }

    /**
     * Records acceptance feedback delivered as one or more inbound acceptance events, already decoded to
     * the neutral payload by a protocol adapter. Acceptance MUST reference an existing exchange <b>owned by the verified caller's
     * tenant AND opened with the verified caller as its counterparty</b> (else 404) — so one counterparty
     * cannot forge another's feedback within the same tenant. The exchange must have reached a fulfillment
     * outcome (enforced by {@link ProviderCertificateExchange#recordAcceptance}). The batch is atomic and
     * duplicate events are ignored.
     */
    @Transactional
    public void recordAcceptances(List<InboundAcceptanceEvent> events, VerifiedRequestContext caller) {
        var pending = new ArrayList<EventBatch.PendingEvent>();
        for (var event : events) {
            pending.add(prepareAcceptance(event.data(), caller, event.dedupKey()));
        }
        EventBatch.applyDeduplicated(pending, eventStore);
    }

    /**
     * Validates one acceptance and resolves the exchange it targets — which must be owned by the verified
     * caller's tenant AND opened with the caller as counterparty (else 404) — then returns the deferred
     * mutation keyed for deduplication. Resolution is eager (a bad request / unknown exchange throws now); only
     * the state change is deferred into the batch.
     */
    private EventBatch.PendingEvent prepareAcceptance(AcceptanceStatusData data, VerifiedRequestContext caller,
                                                      String dedupKey) {
        if (data == null || data.exchangeId() == null) {
            throw badRequest("Acceptance event is missing data.exchangeId");
        }
        if (data.status() == null) {
            throw badRequest("Acceptance event is missing data.status");
        }
        validateAcceptanceErrors(data.status(), data.errors());
        var exchange = exchangeStore.findById(data.exchangeId())
                .filter(e -> e.participantContextId().equals(caller.participantContextId()))
                .filter(e -> caller.subject().equals(e.counterpartyId()))
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + data.exchangeId()));
        return new EventBatch.PendingEvent(dedupKey, () -> {
            exchange.recordAcceptance(data.status(), data.errors());
            exchangeStore.save(exchange);
        });
    }

    /**
     * Single-target lifecycle notify: pushes the certificate's current lifecycle state to <b>one</b>
     * explicitly-named consumer. Purely informational: it opens no exchange and tracks no delivery state —
     * the outcome of this one push is returned synchronously, and the caller retries per counterparty.
     * Certo keeps no registry of counterparties; to reach several consumers, the caller notifies once per
     * consumer. The event payload is derived from the certificate — the light-triage subset of the latest
     * revision for {@code CREATED}/{@code MODIFIED}, the id alone for {@code WITHDRAWN}. The caller's
     * {@code messageId} becomes the pushed event's id verbatim: a retry that reuses it is absorbed by the
     * consumer's dedup, a fresh one is a new event — identity across retries is the caller's to manage.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LifecycleNotification notify(String contextId, String certificateId, NotifyRequest request) {
        if (request == null) {
            throw badRequest("A notify request body is required");
        }
        requireFlow(request.flowId());
        var sender = requireContext(contextId);
        requireUuidMessageId(request.messageId());
        if (request.protocol() == null) {
            throw badRequest("A notify must name the consumer's protocol (the wire-protocol id)");
        }
        if (request.consumerId() == null) {
            throw badRequest("A notify must name the target consumerId (the consumer's DID, the token audience)");
        }
        var certificate = catalog.resolveCertificate(sender.participantContextId(), certificateId);
        var status = certificate.lifecycleStatus();
        var revision = certificate.latestRevision();
        var data = status == LifecycleStatus.WITHDRAWN
                ? new LifecycleStatusData(status, CertificateRecord.idOnly(certificateId))
                : new LifecycleStatusData(status, CertificateRecord.lightTriage(certificateId, revision.revision(),
                        certificate.certificateType(), revision.validFrom(), revision.validUntil()));
        // A transient binding — nothing is persisted, no exchange is opened — carrying the protocol version
        // the dispatcher routes on and the counterparty it addresses.
        var target = new ExchangeBinding(null, certificateId, request.protocol(),
                CounterpartyRole.CONSUMER, request.consumerId());
        var call = new OutboundCall(sender, request.consumerId(), request.flowId());
        var notified = consumerNotifier.notifyLifecycle(target, request.messageId(), data, call);
        return new LifecycleNotification(certificateId, revision.revision(), status, notified);
    }

    /** A {@code messageId} goes on the wire (event id / request message id), so it must be a UUID. */
    private static void requireUuidMessageId(String messageId) {
        if (messageId == null) {
            throw badRequest("A messageId is required (a UUID, reused verbatim on retries)");
        }
        try {
            UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw badRequest("The notify messageId must be a UUID: " + messageId);
        }
    }

    private static PendingRequestView toPendingView(ProviderCertificateExchange exchange) {
        return new PendingRequestView(exchange.exchangeId(), exchange.counterpartyId(), exchange.requestedType(),
                exchange.requestedLocations(), exchange.requestedAt(), exchange.fulfillmentStatus());
    }

    private static CertificateRequestStatus toRequestStatus(ProviderCertificateExchange exchange) {
        // DECLINED never yields a certificate, so certificateId/revision are omitted.
        var yieldsCertificate = exchange.fulfillmentStatus() != DECLINED;
        return new CertificateRequestStatus(
                exchange.exchangeId(),
                yieldsCertificate ? exchange.certificateId() : null,
                yieldsCertificate ? exchange.revision() : null,
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors());
    }

    /**
     * Pushes the current Fulfillment status of a consumer-initiated exchange to the consumer's notification
     * API — the push counterpart of polling. Only the provider-owned terminal outcomes are pushed.
     */
    private void pushFulfillmentStatus(ProviderCertificateExchange exchange, String flowId) {
        var status = exchange.fulfillmentStatus();
        if (status != FULFILLED && status != DECLINED) {
            return; // only push the hand-off and the negative terminal outcome
        }
        var call = new OutboundCall(requireContext(exchange.participantContextId()),
                exchange.counterpartyId(), flowId);
        consumerNotifier.notifyFulfillment(new FulfillmentStatusData(
                exchange.exchangeId(), exchange.certificateId(),
                exchange.certificateId() != null ? exchange.revision() : null,
                status, exchange.fulfillmentErrors()), call);
    }

    /** Whether the exchange's consumer speaks the protocol the (single) acceptance poller speaks. */
    private boolean isPollable(String exchangeId) {
        return bindingStore.resolve(exchangeId, CounterpartyRole.CONSUMER)
                .map(binding -> acceptancePoller.protocolId().equals(binding.protocolId()))
                .orElse(false);
    }

    /** An exchange that must exist within the tenant's scope, else 404 (existence not revealed across tenants). */
    private ProviderCertificateExchange requireExchange(String contextId, String exchangeId) {
        return exchangeStore.findById(exchangeId)
                .filter(e -> e.participantContextId().equals(contextId))
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
    }

    /** Resolves a participant context (loaded, e.g. as an outbound-call sender), else 400 if unknown. */
    private ParticipantContext requireContext(String contextId) {
        requireText(contextId, "A contextId is required");
        return contextStore.find(contextId)
                .orElseThrow(() -> badRequest("Unknown participantContextId: " + contextId));
    }

    /** Fails the request when the named tenant does not exist — an existence check that does not load it. */
    private void requireContextExists(String contextId) {
        requireText(contextId, "A contextId is required");
        if (!contextStore.exists(contextId)) {
            throw badRequest("Unknown participantContextId: " + contextId);
        }
    }

    private static void validateAcceptanceErrors(AcceptanceStatus status, List<StatusError> errors) {
        boolean hasErrors = errors != null && !errors.isEmpty();
        if (status.requiresErrors() && !hasErrors) {
            throw badRequest("Status " + status + " requires a non-empty 'errors' array");
        }
        if (!status.requiresErrors() && hasErrors) {
            throw badRequest("Status " + status + " must not include an 'errors' array");
        }
    }

    private static void requireFlow(String flowId) {
        requireText(flowId, "This operation requires a flowId");
    }
}
