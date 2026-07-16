package org.metaform.certo.provider;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.EventBatch;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.dto.CertificateRequestPage;
import org.metaform.certo.provider.dto.CertificateRequestQuery;
import org.metaform.certo.provider.dto.CertificateRequestResponse;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.dto.CertificatePublication;
import org.metaform.certo.provider.dto.ExchangeView;
import org.metaform.certo.provider.dto.PendingRequestView;
import org.metaform.certo.provider.dto.PublishRequest;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.metaform.certo.provider.store.ExchangeSpecifications;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.metaform.certo.common.TransactionSupport.afterCommit;
import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATION_REQUESTED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FAILED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;
import static org.metaform.certo.common.web.ApiException.badRequest;

/**
 * The provider's <b>certificate exchange</b> lifecycle (CX-0135 &sect;2.1): opening consumer-initiated
 * requests, the backend fulfill/fail/decline outcomes and the fulfillment-status push, recording inbound
 * acceptance feedback, the request-backlog queries, and the provider-initiated lifecycle publish. Consults
 * {@link ProviderCatalogService} for the held certificate an exchange is fulfilled with.
 */
@Service
@Transactional
public class ProviderExchangeService {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderExchangeService.class);

    private final ProviderCertificateExchangeStore exchangeStore;
    private final ExchangeBindingStore bindingStore;
    private final ProcessedEventStore eventStore;
    private final ParticipantContextStore contextStore;
    private final ConsumerNotifier consumerNotifications;
    private final CloudEventCodec codec;
    private final ProviderCatalogService catalog;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProviderExchangeService(ProviderCertificateExchangeStore exchangeStore,
                                   ExchangeBindingStore bindingStore,
                                   ProcessedEventStore eventStore,
                                   ParticipantContextStore contextStore,
                                   ConsumerNotifier consumerNotifications,
                                   CloudEventCodec codec,
                                   ProviderCatalogService catalog,
                                   PlatformTransactionManager txManager,
                                   Clock clock) {
        this.exchangeStore = exchangeStore;
        this.bindingStore = bindingStore;
        this.eventStore = eventStore;
        this.contextStore = contextStore;
        this.consumerNotifications = consumerNotifications;
        this.codec = codec;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange} (CX-0135 &sect;3.3.1). A held certificate
     * covering the requested locations &rarr; immediate {@code FULFILLED}; otherwise
     * {@code CERTIFICATION_REQUESTED} — the exchange waits for the backend to {@code addCertificate} the
     * certificate, {@link #failRequest fail}, or {@link #declineRequest decline} it.
     *
     * <p>Idempotent (CX-0135 &sect;2.1.1): a repeated request from the same counterparty for the same
     * {@code certificateType} + locations reuses the still-live exchange — pending or already {@code FULFILLED}
     * — rather than opening a duplicate, so a retried open returns the same exchange. A new exchange is opened
     * only once the prior one reaches a terminal outcome (a re-attempt).
     */
    public CertificateRequestResponse requestCertificate(CertificateRequest request, VerifiedRequestContext requestContext) {
        ApiException.requireText(request.certificateType(), "A certificate request must specify a certificateType");
        // The addressed provider tenant (the verified audience) owns the exchange; the verified caller is the
        // requesting consumer (the counterparty). The verifier already resolved this participant context from
        // the token audience, so it is non-null and known to exist — no re-check needed.
        var contextId = requestContext.participantContextId();
        // The counterparty's BPN and DID both come from the verified token — never resolved later.
        var counterparty = requestContext.bpnOrSubject();
        var counterpartyDid = requestContext.subject();
        var requestedLocations = request.certifiedLocations();
        var requestKey = requestKey(request.certificateType(), requestedLocations);

        // Idempotent open (CX-0135 §2.1.1): a repeated request from the same counterparty for the same
        // certificateType + locations reuses the still-live exchange — pending or already FULFILLED — rather
        // than opening a duplicate, so a retried open returns the same exchange. A new one is opened only when
        // the prior match reached a terminal outcome (a re-attempt). The counterparty scoping means this only
        // ever collapses one consumer's own repeats.
        var existing = exchangeStore
                .findByParticipantContextIdAndCounterpartyBpnAndRequestKey(contextId, counterparty, requestKey)
                .stream()
                .filter(ProviderCertificateExchange::isLive)
                .min(Comparator.comparing(ProviderCertificateExchange::exchangeId));
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // No live exchange: a held certificate covering the request fulfills immediately, otherwise the request
        // is submitted for certification (pending).
        var held = catalog.findCertificateForLocations(contextId, request.certificateType(), requestedLocations);
        if (held.isPresent()) {
            var certificate = held.get();
            var exchange = new ProviderCertificateExchange(UUID.randomUUID().toString(),
                    contextId,
                    certificate.certificateId(),
                    certificate.latestRevision().revision(),
                    counterparty,
                    counterpartyDid,
                    FULFILLED);
            exchange.markConsumerInitiated();
            exchange.assignRequestKey(requestKey);
            exchangeStore.save(exchange);
            return toResponse(exchange);
        }

        var exchange = ProviderCertificateExchange.pending(UUID.randomUUID().toString(),
                contextId,
                counterparty,
                counterpartyDid,
                request.certificateType(),
                requestedLocations,
                OffsetDateTime.now(clock));
        exchange.assignRequestKey(requestKey);
        exchangeStore.save(exchange);
        return toResponse(exchange);
    }

    /** Renders an exchange as a request response — the current fulfillment status and, once known, the certificate. */
    private static CertificateRequestResponse toResponse(ProviderCertificateExchange exchange) {
        var certificateId = exchange.certificateId();
        return new CertificateRequestResponse(exchange.exchangeId(),
                certificateId,
                certificateId != null ? exchange.revision() : null,
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors());
    }

    /**
     * Canonical dedup key for a consumer request: its {@code certificateType} plus requested locations,
     * order-insensitive and de-duplicated (an omitted/empty location set — the legal entity — is distinct
     * from any specific location). Used to reuse a still-live exchange for a repeated request.
     */
    private static String requestKey(String certificateType, List<String> locations) {
        var normalized = locations.stream()
                .filter(location -> location != null && !location.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        return certificateType + ' ' + String.join(",", normalized);
    }

    /**
     * Backend outcome — the certification authority could not issue the certificate: fails a waiting exchange
     * ({@code CERTIFICATION_REQUESTED → FAILED}) and pushes the terminal status to the consumer over
     * {@code flowId}. Illegal once the exchange has reached a terminal state (409).
     */
    public CertificateRequestStatus failRequest(String contextId, String exchangeId, String flowId, String reason) {
        requireFlow(flowId);
        var exchange = requireExchange(contextId, exchangeId);
        var message = (reason == null || reason.isBlank())
                ? "The certification authority could not issue the certificate" : reason;
        exchange.transitionFulfillment(FAILED, List.of(new StatusError(message)));
        exchangeStore.save(exchange);
        // Push once the transition is committed, so the DB connection is not held across the outbound call.
        afterCommit(() -> pushFulfillmentStatus(exchange, flowId));
        return toRequestStatus(exchange);
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
     * that covers it ({@code CERTIFICATION_REQUESTED → FULFILLED}) and pushes {@code FULFILLED} over
     * {@code flowId}. Rejected (409) if the exchange is not awaiting fulfillment, or no held certificate
     * covers the request yet.
     */
    public CertificateRequestStatus fulfill(String contextId, String exchangeId, String flowId) {
        requireFlow(flowId);
        var exchange = requireExchange(contextId, exchangeId);
        if (!exchange.isConsumerInitiated() || exchange.fulfillmentStatus() != CERTIFICATION_REQUESTED) {
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
     * still {@code CERTIFICATION_REQUESTED}), optionally narrowed by {@code certificateType} and
     * {@code certifiedLocations}. Not part of CX-0135.
     */
    @Transactional(readOnly = true)
    public CertificateRequestPage queryRequests(String contextId, CertificateRequestQuery query) {
        requireContextExists(contextId);
        var status = (query != null && query.status() != null) ? query.status() : CERTIFICATION_REQUESTED;
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
     * and certified locations). The primary driver of the issue&rarr;notify loop. Not part of CX-0135.
     */
    @Transactional(readOnly = true)
    public CertificateRequestPage fulfillableRequests(String contextId, String certificateId) {
        var certificate = catalog.resolveCertificate(contextId, certificateId);
        var coveredBpns = certificate.certifiedLocations().stream()
                .flatMap(l -> Stream.of(l.bpnl(), l.bpna(), l.bpns()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var spec = ExchangeSpecifications.fulfillableBy(contextId, certificate.certificateType(), coveredBpns);
        var items = exchangeStore.findAll(spec, Sort.by("exchangeId")).stream()
                .map(ProviderExchangeService::toPendingView)
                .toList();
        return new CertificateRequestPage(items);
    }

    /** Returns the provider's full view of an exchange — both phases (management/inspection; not in CX-0135). */
    @Transactional(readOnly = true)
    public ExchangeView getExchangeView(String contextId, String exchangeId) {
        var exchange = requireExchange(contextId, exchangeId);
        return new ExchangeView(exchange.exchangeId(), exchange.certificateId(), exchange.revision(),
                exchange.fulfillmentStatus(), exchange.fulfillmentErrors(),
                exchange.acceptanceStatus(), exchange.acceptanceErrors());
    }

    /** Returns the current fulfillment status of an exchange (CX-0135 &sect;3.3.1.1). */
    @Transactional(readOnly = true)
    public CertificateRequestStatus getRequestStatus(String exchangeId) {
        var exchange = exchangeStore.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return toRequestStatus(exchange);
    }

    /**
     * Records acceptance feedback delivered as one or more {@code CertificateAcceptanceStatus} CloudEvents
     * (CX-0135 &sect;3.3.5). Acceptance MUST reference an existing exchange <b>owned by the verified caller's
     * tenant</b> (else 404). The batch is atomic and duplicate events are ignored.
     */
    @Transactional
    public void recordAcceptance(byte[] body, String contextId) {
        var pending = new ArrayList<EventBatch.PendingEvent>();
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            if (!CcmEvents.TYPE_ACCEPTANCE_STATUS.equals(type)) {
                throw badRequest("Unexpected event type for acceptance endpoint: " + type);
            }
            var event = codec.decode(node, AcceptanceStatusData.class);
            var data = event.data();
            if (data == null || data.exchangeId() == null) {
                throw badRequest("Acceptance event is missing data.exchangeId");
            }
            if (data.status() == null) {
                throw badRequest("Acceptance event is missing data.status");
            }
            validateAcceptanceErrors(data.status(), data.errors());
            var exchange = exchangeStore.find(data.exchangeId())
                    .filter(e -> e.participantContextId().equals(contextId))
                    .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + data.exchangeId()));
            if (exchange.fulfillmentStatus() != FULFILLED) {
                throw ApiException.conflict("Exchange " + data.exchangeId() + " is not FULFILLED"
                                            + " (current fulfillment status: " + exchange.fulfillmentStatus() + ")");
            }
            pending.add(new EventBatch.PendingEvent(codec.dedupKey(event), () -> {
                exchange.recordAcceptance(data.status(), data.errors());
                exchangeStore.save(exchange);
            }));
        }
        EventBatch.applyDeduplicated(pending, eventStore);
    }

    /**
     * Provider-initiated push: notifies <b>one explicitly-named target consumer</b> of a certificate
     * lifecycle event (CX-0135 &sect;2.1.1 / &sect;3.2.1). The {@code lifecycleStatus} ({@code CREATED} opens
     * an exchange to accept; {@code MODIFIED}/{@code WITHDRAWN} are one-way), protocol {@code version},
     * whether the certificate is {@code embedded} or by reference, and the {@code revision} are all chosen by
     * the caller. This call only notifies; the artifact state change is a separate catalog operation.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CertificatePublication publish(String contextId, String certificateId, PublishRequest request) {
        var req = request != null ? request : PublishRequest.defaults();
        var lifecycle = req.lifecycleStatus();
        var version = req.protocolVersion();
        requireFlow(req.flowId());
        var sender = requireContext(contextId);
        if (req.consumerBpn() == null) {
            throw badRequest("A publish must name the target consumerBpn");
        }
        if (req.consumerDid() == null) {
            throw badRequest("A publish must name the target consumerDid (the token audience)");
        }

        String exchangeId = null;
        int revision;
        LifecycleStatusData data;
        if (lifecycle == LifecycleStatus.WITHDRAWN) {
            var certificate = catalog.resolveCertificate(sender.participantContextId(), certificateId);
            revision = certificate.latestRevision().revision();
            data = new LifecycleStatusData(LifecycleStatus.WITHDRAWN, null, CertificateRecord.idOnly(certificateId));
        } else {
            var certificate = catalog.resolveCertificate(sender.participantContextId(), certificateId);
            if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
                throw ApiException.conflict("Certificate " + certificateId + " has been withdrawn");
            }
            var rev = requireRevision(certificate, req.revision());
            revision = rev.revision();
            var certData = req.embedded()
                    ? catalog.toRecordWithContent(certificate, rev)
                    : CertificateRecord.lightTriage(certificateId, rev.revision(), certificate.certificateType(),
                    rev.validFrom(), rev.validUntil());
            if (lifecycle == LifecycleStatus.CREATED) {
                // A CREATED push opens an exchange (the consumer accepts it, closing the loop).
                exchangeId = UUID.randomUUID().toString();
            }
            data = new LifecycleStatusData(lifecycle, exchangeId, certData);
        }

        var target = new ExchangeBinding(exchangeId, certificateId, version, CounterpartyRole.CONSUMER,
                req.consumerBpn(), null);
        // Persist the opened exchange and its binding in one transaction, then notify once committed so the DB
        // connection is not held across the outbound push. Only a CREATED push opens an exchange; a non-native
        // consumer's binding lets its acceptance /status correlate back.
        var openedExchangeId = exchangeId;
        var openedRevision = revision;
        tx.executeWithoutResult(status -> {
            if (openedExchangeId != null) {
                exchangeStore.save(new ProviderCertificateExchange(openedExchangeId,
                        sender.participantContextId(),
                        certificateId,
                        openedRevision,
                        req.consumerBpn(),
                        req.consumerDid(),
                        FULFILLED));
                if (version != ProtocolVersion.NATIVE) {
                    bindingStore.record(target);
                }
            }
        });
        var call = new OutboundCall(sender, req.consumerBpn(), req.consumerDid(), req.flowId());
        var notified = consumerNotifications.notifyLifecycle(target, data, call);
        return new CertificatePublication(exchangeId, certificateId, revision, notified);
    }

    private static PendingRequestView toPendingView(ProviderCertificateExchange exchange) {
        return new PendingRequestView(exchange.exchangeId(), exchange.counterpartyBpn(), exchange.requestedType(),
                exchange.requestedLocations(), exchange.requestedAt(), exchange.fulfillmentStatus());
    }

    private static CertificateRequestStatus toRequestStatus(ProviderCertificateExchange exchange) {
        // DECLINED/FAILED never yield a certificate, so certificateId/revision are omitted (CX-0135 §4.4.1/§4.4.2).
        var yieldsCertificate = exchange.fulfillmentStatus() != DECLINED && exchange.fulfillmentStatus() != FAILED;
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
        if (!exchange.isConsumerInitiated()) {
            return;
        }
        var status = exchange.fulfillmentStatus();
        if (status != FULFILLED && status != FAILED && status != DECLINED) {
            return; // only push terminal outcomes
        }
        var call = new OutboundCall(requireContext(exchange.participantContextId()),
                exchange.counterpartyBpn(), exchange.counterpartyDid(), flowId);
        consumerNotifications.notifyFulfillment(new FulfillmentStatusData(
                exchange.exchangeId(), exchange.certificateId(), status, exchange.fulfillmentErrors()), call);
    }

    /** An exchange that must exist within the tenant's scope, else 404 (existence not revealed across tenants). */
    private ProviderCertificateExchange requireExchange(String contextId, String exchangeId) {
        return exchangeStore.find(exchangeId)
                .filter(e -> e.participantContextId().equals(contextId))
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
    }

    /** Resolves a participant context (loaded, e.g. as an outbound-call sender), else 400 if unknown. */
    private ParticipantContext requireContext(String contextId) {
        ApiException.requireText(contextId, "A contextId is required");
        return contextStore.find(contextId)
                .orElseThrow(() -> badRequest("Unknown participantContextId: " + contextId));
    }

    /** Fails the request when the named tenant does not exist — an existence check that does not load it. */
    private void requireContextExists(String contextId) {
        ApiException.requireText(contextId, "A contextId is required");
        if (!contextStore.exists(contextId)) {
            throw badRequest("Unknown participantContextId: " + contextId);
        }
    }

    /** Resolves a specific revision, or the latest when {@code revision <= 0} (the default/unspecified). */
    private static CertificateRevision requireRevision(Certificate certificate, int revision) {
        return (revision <= 0)
                ? certificate.latestRevision()
                : certificate.revision(revision).orElseThrow(() -> ApiException.notFound(
                        "Unknown revision " + revision + " for certificate " + certificate.certificateId()));
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
        ApiException.requireText(flowId, "This operation requires a flowId");
    }
}
