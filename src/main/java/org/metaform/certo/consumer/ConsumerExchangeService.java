package org.metaform.certo.consumer;

import org.metaform.certo.common.cloudevent.EventBatch;
import org.metaform.certo.common.cloudevent.store.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.CertificateTypes;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.domain.ConsumerCertificateExchange;
import org.metaform.certo.consumer.dto.CertificateAcceptanceStatusResponse;
import org.metaform.certo.consumer.dto.ConsumerExchangePage;
import org.metaform.certo.consumer.dto.ConsumerExchangeQuery;
import org.metaform.certo.consumer.dto.ConsumerExchangeView;
import org.metaform.certo.consumer.spi.AcceptanceReporter;
import org.metaform.certo.consumer.spi.InboundNotification;
import org.metaform.certo.consumer.spi.InboundNotificationListener;
import org.metaform.certo.consumer.spi.InboundProtocolEvent;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.consumer.store.ConsumerCertificateExchangeStore;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.DispatchingCertificateRequester;
import org.metaform.certo.protocol.DispatchingCertificateRetriever;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;
import static org.metaform.certo.common.model.LifecycleStatus.AVAILABLE;
import static org.metaform.certo.common.util.TransactionSupport.afterCommit;
import static org.metaform.certo.common.web.ApiException.badRequest;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * The consumer's {@code Certificate Exchange} lifecycle (the SCE specification, &sect;3.2). It is a <b>pure
 * mechanism</b>: consumer-initiated requests (pull), the inbound lifecycle/fulfillment CloudEvents it
 * records on its state and emits to {@link InboundNotificationListener}s, and the management operations a
 * client drives — {@link #retrieve} (pull the certificate + documents for inspection) and {@link #accept}
 * (record + report the client's decision). The consumer <b>never decides acceptance itself</b>. Inbound
 * lifecycle events update the known-certificate view via {@link ConsumerCatalogService}.
 */
@Service
@Transactional
public class ConsumerExchangeService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerExchangeService.class);

    /**
     * Upper bound on a single reconciliation-query result set (the client acts on a batch, then re-queries).
     */
    private static final int MAX_QUERY_RESULTS = 500;

    private final ConsumerCertificateExchangeStore exchangeStore;
    private final ProcessedEventStore eventStore;
    private final DispatchingCertificateRetriever providerClient;
    private final DispatchingCertificateRequester requestClient;
    private final AcceptanceReporter acceptanceClient;
    private final ParticipantContextStore contextStore;
    private final ConsumerCatalogService catalog;
    private final ExchangeBindingStore bindings;
    private final List<InboundNotificationListener> listeners;
    private final TransactionTemplate tx;

    public ConsumerExchangeService(ConsumerCertificateExchangeStore exchangeStore,
                                   ProcessedEventStore eventStore,
                                   DispatchingCertificateRetriever providerClient,
                                   DispatchingCertificateRequester requestClient,
                                   AcceptanceReporter acceptanceClient,
                                   ParticipantContextStore contextStore,
                                   ConsumerCatalogService catalog,
                                   ExchangeBindingStore bindings,
                                   List<InboundNotificationListener> listeners,
                                   PlatformTransactionManager txManager) {
        this.exchangeStore = exchangeStore;
        this.eventStore = eventStore;
        this.providerClient = providerClient;
        this.requestClient = requestClient;
        this.acceptanceClient = acceptanceClient;
        this.contextStore = contextStore;
        this.catalog = catalog;
        this.bindings = bindings;
        this.listeners = listeners;
        // REQUIRES_NEW: this template is used from an afterCommit callback (the acceptance-report mark), where
        // the outer transaction has already committed; a plain REQUIRED template would join that completed
        // transaction and never flush the change. A fresh transaction commits the mark on its own.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Consumer-initiated pull: opens a certificate request on the provider and records
     * the resulting exchange (mirroring the returned fulfillment status). The consumer never decides
     * acceptance itself — once an exchange is {@code FULFILLED}, a client drives {@code retrieve} + {@code accept}.
     */
    // Non-transactional: the remote request runs between short store transactions (context lookup, then the
    // exchange save) so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConsumerCertificateExchange initiateRequest(String contextId,
                                                       String messageId,
                                                       String providerId,
                                                       String certificateType,
                                                       String flowId,
                                                       List<LocationFilter> certifiedLocations,
                                                       String protocolId) {
        var sender = requireContext(contextId);
        requireProtocol(protocolId);
        requireUuidMessageId(messageId);
        if (!CertificateTypes.isValid(certificateType)) {
            throw badRequest("certificateType must be an opaque code of lowercase letters and digits only: " + certificateType);
        }
        var call = new OutboundCall(sender, providerId, flowId);
        ProviderRequestResult result;
        try {
            result = requestClient.request(protocolId, messageId, certificateType, certifiedLocations, call);
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Provider request failed: " + e.getMessage());
        }
        var exchange = new ConsumerCertificateExchange(result.exchangeId(),
                result.certificateId(),
                result.revision(),
                result.status(),
                result.errors(),
                contextId,
                providerId);
        exchangeStore.save(exchange);
        // Record the protocol binding so every later outbound on this exchange (poll, retrieve, acceptance
        // report) routes to the right adapter — keyed by exchangeId, counterparty = the provider.
        bindings.record(new ExchangeBinding(result.exchangeId(), result.certificateId(), protocolId,
                CounterpartyRole.PROVIDER, providerId));
        // An immediately-FULFILLED 201 embeds the full record — no separate retrieval needed for metadata.
        recordEmbeddedCertificate(result.certificate(), contextId);
        return exchange;
    }

    /**
     * Polls the provider for the latest fulfillment status of a consumer-initiated request.
     */
    // Non-transactional: load the exchange, poll the provider outside any transaction, then save the mirrored
    // status — so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConsumerCertificateExchange pollRequest(String contextId, String exchangeId, String flowId) {
        var exchange = findConsumerRequest(contextId, exchangeId);
        try {
            var result = requestClient.pollStatus(exchangeId, outboundCall(exchange, flowId));
            exchange.updateFulfillment(result.status(), result.certificateId(), result.revision(), result.errors());
            exchangeStore.save(exchange);
            // A FULFILLED poll response embeds the full record — sync the known-certificate view.
            recordEmbeddedCertificate(result.certificate(), contextId);
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Provider poll failed: " + e.getMessage());
        }
        return exchange;
    }

    @Transactional(readOnly = true)
    public ConsumerCertificateExchange getRequest(String contextId, String exchangeId) {
        return findConsumerRequest(contextId, exchangeId);
    }

    private ConsumerCertificateExchange findConsumerRequest(String contextId, String exchangeId) {
        return exchangeStore.findById(exchangeId)
                .filter(e -> contextId.equals(e.participantContextId()))
                .orElseThrow(() -> ApiException.notFound("Unknown request exchangeId: " + exchangeId));
    }

    /**
     * Handles one or more inbound notification events, already decoded to the neutral payloads by a
     * protocol adapter (see {@link InboundProtocolEvent}). The whole batch runs in one transaction: every event is validated before any is applied, then each is claimed
     * and applied. Because dedup and every side effect share the transaction, a failure anywhere rolls the
     * batch back whole — including the dedup markers — so a retry re-applies all of it and nothing is lost
     * or double-applied. Duplicate events (same {@code source}+{@code id}) are skipped by the dedup claim.
     */
    @Transactional
    public void handleNotifications(List<InboundProtocolEvent> events, VerifiedRequestContext requestContext) {
        var pending = new ArrayList<EventBatch.PendingEvent>();
        for (var event : events) {
            switch (event) {
                case InboundProtocolEvent.Lifecycle(var data, var dedupKey) -> {
                    validateLifecycle(data);
                    pending.add(new EventBatch.PendingEvent(dedupKey, () -> applyLifecycle(data, requestContext)));
                }
                case InboundProtocolEvent.Fulfillment(var data, var dedupKey) -> {
                    validateFulfillment(data);
                    pending.add(new EventBatch.PendingEvent(dedupKey, () -> applyFulfillment(data, requestContext)));
                }
            }
        }
        // Claim + apply each event in this transaction: a throw rolls the whole batch back (claims included),
        // so a failed event is re-applied on retry rather than silently skipped.
        EventBatch.applyDeduplicated(pending, eventStore);
    }

    /**
     * Emits a recorded inbound event to every registered listener, <b>after the inbound transaction
     * commits</b>: a listener (e.g. the webhook) must not run while the DB connection is held, and must never
     * fire for a batch that later rolls back (which would leave a client reacting to an exchange that does not
     * exist). Best-effort — a failing listener never disrupts the inbound acknowledgement; the event is
     * already recorded and reconcilable.
     */
    private void emit(InboundNotification event) {
        afterCommit(() -> notifyListeners(event));
    }

    private void notifyListeners(InboundNotification event) {
        for (var listener : listeners) {
            try {
                listener.onNotification(event);
            } catch (RuntimeException e) {
                LOG.warn("Inbound notification listener failed for exchange {}: {}", event.exchangeId(), e.getMessage());
            }
        }
    }

    /**
     * Returns the consumer's acceptance decision for an exchange. Returns
     * {@code 404} until the Acceptance phase has begun (the exchange may still be in Fulfillment).
     */
    @Transactional(readOnly = true)
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(String contextId, String exchangeId) {
        var exchange = exchangeStore.findById(exchangeId)
                .filter(e -> contextId != null && contextId.equals(e.participantContextId()))
                .filter(e -> e.acceptanceStatus() != null)
                .orElseThrow(() -> ApiException.notFound("No acceptance status for exchange: " + exchangeId));
        return new CertificateAcceptanceStatusResponse(
                exchange.exchangeId(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    /**
     * Consumer-side reconciliation query: the exchanges awaiting the caller's action — {@code FULFILLED} but
     * not yet accepted (outstanding retrieve/accept), or accepted but not confirmed reported (needs
     * re-reporting). The safety net for a dropped notification callback or a lost acceptance report. Pass
     * {@code awaitingAcceptanceOnly=false} for all tenant exchanges. Scoped and bounded in the
     * database; results are capped at {@value #MAX_QUERY_RESULTS} (a client that hits the cap acts on the
     * returned batch, which shrinks it, and queries again).
     */
    @Transactional(readOnly = true)
    public ConsumerExchangePage queryExchanges(String contextId, ConsumerExchangeQuery query) {
        var awaitingOnly = query == null || query.awaitingAcceptanceOnly() == null || query.awaitingAcceptanceOnly();
        var pageable = PageRequest.of(0, MAX_QUERY_RESULTS, Sort.by("exchangeId"));
        var rows = awaitingOnly
                ? exchangeStore.findAwaitingAction(contextId, pageable)
                : exchangeStore.findByParticipantContextId(contextId, pageable);
        return new ConsumerExchangePage(rows.stream().map(ConsumerExchangeView::of).toList());
    }

    /**
     * Management-driven retrieval: fetches an exchange's certificate (and documents) from the provider over
     * the given live {@code flowId}, for the caller to inspect before deciding acceptance. Used by a plugged-in
     * client reacting to an {@link InboundNotification}.
     */
    // Non-transactional: load the exchange in its own short transaction, then fetch from the provider outside
    // any transaction so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RetrievedCertificate retrieve(String contextId, String exchangeId, String flowId) {
        var exchange = requireOwnedExchange(contextId, exchangeId);
        if (exchange.certificateId() == null) {
            throw ApiException.conflict("Exchange " + exchangeId + " has no certificate to retrieve yet");
        }
        try {
            return providerClient.fetchForExchange(exchange.exchangeId(), exchange.certificateId(),
                    exchange.revision(), outboundCall(exchange, flowId));
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Retrieval failed: " + e.getMessage());
        }
    }

    /**
     * Management-driven acceptance: records the caller's decision for an exchange and reports it to the
     * provider over the given live {@code flowId}. The decision (ACCEPTED / REJECTED) is the caller's —
     * the consumer applies no rule of its own.
     *
     * <p>The report is <b>best-effort</b> (post-commit), so a lost report leaves the acceptance recorded here
     * but unknown to the provider. Recovery is by polling, not a durable outbox: {@link #queryExchanges}
     * surfaces such exchanges for a re-drive, and the provider can pull the verdict via its
     * {@code poll-acceptance} op ({@code GET /certificate-acceptance-status/{id}}).
     */
    public void accept(String contextId,
                       String exchangeId,
                       AcceptanceStatus status,
                       List<StatusError> errors,
                       String flowId) {
        var exchange = requireOwnedExchange(contextId, exchangeId);
        // A re-drive with the same verdict (e.g. reconciling an acceptance that was recorded but whose report
        // was lost) is not a fresh transition — skip it (avoid a 409) and just re-report over the new flowId.
        // The provider dedups the re-report (its acceptance CloudEvent id is stable per exchange).
        if (status != exchange.acceptanceStatus()) {
            exchange.transitionAcceptance(status, errors);
            exchangeStore.save(exchange);
        }
        var call = outboundCall(exchange, flowId);
        // Report once the acceptance is committed, so the DB connection is not held across the outbound call;
        // mark it reported (in its own transaction) ONLY if delivery succeeded, so a lost report stays surfaced
        // by the reconciliation query ({@link #queryExchanges}) for a re-drive.
        afterCommit(() -> {
            if (acceptanceClient.report(exchangeId, status, call, errors)) {
                tx.executeWithoutResult(_ -> exchangeStore.findById(exchangeId)
                        .ifPresent(ConsumerCertificateExchange::markAcceptanceReported));
            }
        });
    }

    /**
     * Proactive discovery pull (consumer-initiated with no prior notification): pulls a partner's certificate
     * over the caller-supplied live {@code flowId} in the stated {@code protocol}, records it in the
     * tenant's known-certificate view, and returns it for display. The flow is established upstream by the
     * caller; this performs the data-plane read. Used to satisfy a "retrieve &amp; display a partner's
     * certificate" without a push.
     */
    // Non-transactional across the outbound call: pull outside any transaction, then record the known
    // certificate in its own short transaction, so no DB connection is held across the provider read.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RetrievedCertificate pullCertificate(String contextId,
                                                String partnerId,
                                                String flowId,
                                                String protocolId) {
        var sender = requireContext(contextId);
        requireProtocol(protocolId);
        var call = new OutboundCall(sender, partnerId, flowId);
        RetrievedCertificate retrieved;
        try {
            // No exchange/certificateId is known ahead of a discovery pull; the retriever derives the id.
            retrieved = providerClient.fetch(protocolId, null, call);
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Certificate pull failed: " + e.getMessage());
        }
        tx.executeWithoutResult(_ ->
                catalog.recordKnownCertificate(new LifecycleStatusData(AVAILABLE, retrieved.metadata()), contextId));
        return retrieved;
    }

    /**
     * Syncs the known-certificate view from a certificate embedded in an Exchange representation or
     * fulfillment event. Only a full record is recorded ({@code certificateType} present) — the
     * id/revision reference form carries nothing worth recording.
     */
    private void recordEmbeddedCertificate(CertificateRecord certificate, String contextId) {
        if (certificate != null && certificate.certificateType() != null) {
            catalog.recordKnownCertificate(new LifecycleStatusData(AVAILABLE, certificate), contextId);
        }
    }

    private void validateLifecycle(LifecycleStatusData data) {
        if (data == null || data.status() == null || data.certificate() == null
            || data.certificate().certificateId() == null) {
            throw badRequest("Lifecycle event is missing status or certificate.certificateId");
        }
    }

    /**
     * A lifecycle event is purely informational: it never opens, alters, or closes an exchange. The
     * known-certificate view is updated and the event is emitted; a client that wants the certificate opens
     * an exchange via {@link #initiateRequest}.
     */
    private void applyLifecycle(LifecycleStatusData data, VerifiedRequestContext requestContext) {
        // Keep the consumer's known-certificate view in sync (AVAILABLE/WITHDRAWN), owned by the
        // receiving tenant (the verified audience).
        catalog.recordKnownCertificate(data, requestContext.participantContextId());
        var cert = data.certificate();
        // Record + emit only. A plugged-in client reacts via the management API with its own flowId.
        emit(new InboundNotification(InboundNotification.Kind.LIFECYCLE,
                null,
                cert.certificateId(),
                cert.revision(),
                data.status().name(),
                requestContext.subject()));
    }

    private void validateFulfillment(FulfillmentStatusData data) {
        if (data == null || data.exchangeId() == null || data.status() == null) {
            throw badRequest("Fulfillment event is missing exchangeId or status");
        }
        // PENDING is resource state, never an event: it is only entered at creation and reported in the
        // 201 response or via the exchange status endpoint.
        if (data.status() != FULFILLED && data.status() != DECLINED) {
            throw badRequest("Status " + data.status() + " cannot be pushed; a fulfillment event reports FULFILLED or DECLINED");
        }
        if (data.status() == FULFILLED
            && (data.certificate() == null || data.certificateId() == null || data.revision() == null)) {
            throw badRequest("A FULFILLED event must carry the certificate (certificateId and revision)");
        }
        var hasErrors = data.errors() != null && !data.errors().isEmpty();
        if (data.status() == DECLINED && !hasErrors) {
            throw badRequest("Status " + data.status() + " requires a non-empty 'errors' array");
        }
    }

    /**
     * A pushed Fulfillment status for a consumer-initiated exchange. Correlates by
     * {@code exchangeId} to a tracked request and mirrors the status, then records + emits — the consumer
     * never decides acceptance itself. An event for an exchange the consumer didn't open is ignored.
     */
    private void applyFulfillment(FulfillmentStatusData data, VerifiedRequestContext requestContext) {
        // Only a fulfillment for an exchange owned by the receiving tenant (the verified audience) AND opened
        // with the calling provider (the verified caller's DID) is applied — so a rogue provider cannot
        // overwrite another provider's exchange within this tenant.
        var contextId = requestContext.participantContextId();
        var callerId = requestContext.subject();
        var exchange = exchangeStore.findById(data.exchangeId())
                .filter(e -> contextId.equals(e.participantContextId()))
                .filter(e -> callerId.equals(e.providerId()))
                .orElse(null);
        if (exchange == null) {
            LOG.info("Fulfillment status {} for unknown exchange {} — ignored", data.status(), data.exchangeId());
            return;
        }
        exchange.updateFulfillment(data.status(), data.certificateId(), data.revision(), data.errors());
        exchangeStore.save(exchange);
        // A FULFILLED push delivers the full record — sync the known-certificate view so the client needs
        // no separate retrieval for the metadata (documents stay pull-only).
        recordEmbeddedCertificate(data.certificate(), requestContext.participantContextId());
        // Record + emit only; a plugged-in client drives retrieve/accept via management with its own flowId.
        emit(new InboundNotification(InboundNotification.Kind.FULFILLMENT, exchange.exchangeId(), exchange.certificateId(),
                exchange.revision(), data.status().name(), requestContext.subject()));
    }

    /**
     * An exchange that must exist within the tenant's scope, else 404 (existence not revealed across tenants).
     */
    private ConsumerCertificateExchange requireOwnedExchange(String contextId, String exchangeId) {
        return exchangeStore.findById(exchangeId)
                .filter(e -> contextId.equals(e.participantContextId()))
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
    }

    /**
     * Builds an {@link OutboundCall} to the exchange's provider on behalf of the exchange's own tenant.
     */
    private OutboundCall outboundCall(ConsumerCertificateExchange exchange, String flowId) {
        return new OutboundCall(requireContext(exchange.participantContextId()),
                exchange.providerId(), flowId);
    }

    /** A {@code messageId} goes on the wire as the request message id, so it must be a UUID. */
    private static void requireUuidMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw badRequest("A messageId is required (a UUID, reused verbatim on retries)");
        }
        try {
            java.util.UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            throw badRequest("The messageId must be a UUID: " + messageId);
        }
    }

    /** The wire protocol is the caller's to state — there is no default. */
    private static void requireProtocol(String protocolId) {
        if (protocolId == null || protocolId.isBlank()) {
            throw badRequest("A protocol is required (the counterparty's wire-protocol id)");
        }
    }

    /**
     * Resolves a participant context, failing the request when the named tenant does not exist.
     */
    private ParticipantContext requireContext(String contextId) {
        if (contextId == null) {
            throw badRequest("A contextId is required");
        }
        return contextStore.find(contextId)
                .orElseThrow(() -> badRequest("Unknown contextId: " + contextId));
    }
}
