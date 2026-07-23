package org.metaform.certo.consumer;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.EventBatch;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.dto.CertificateAcceptanceStatusResponse;
import org.metaform.certo.consumer.dto.ConsumerExchangePage;
import org.metaform.certo.consumer.dto.ConsumerExchangeQuery;
import org.metaform.certo.consumer.dto.ConsumerExchangeView;
import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.metaform.certo.consumer.spi.AcceptanceReporter;
import org.metaform.certo.consumer.spi.CertificateRequester;
import org.metaform.certo.consumer.spi.CertificateRetriever;
import org.metaform.certo.consumer.spi.InboundCcmEvent;
import org.metaform.certo.consumer.spi.InboundNotificationListener;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.consumer.spi.RetrievedDocument;
import org.metaform.certo.consumer.store.ConsumerCertificateExchangeStore;
import org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec;
import org.metaform.certo.protocol.ccm300.model.Ccm300LifecycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.metaform.certo.common.TransactionSupport.afterCommit;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * The consumer's {@code Certificate Exchange} lifecycle (CX-0135 v3, &sect;3.2). It is a <b>pure
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

    /** Upper bound on a single reconciliation-query result set (the client acts on a batch, then re-queries). */
    private static final int MAX_QUERY_RESULTS = 500;

    private final ConsumerCertificateExchangeStore exchangeStore;
    private final CloudEventCodec codec;
    private final ProcessedEventStore eventStore;
    private final CertificateRetriever providerClient;
    private final CertificateRequester requestClient;
    private final AcceptanceReporter acceptanceClient;
    private final ParticipantContextStore contextStore;
    private final ConsumerCatalogService catalog;
    private final List<InboundNotificationListener> listeners;
    private final TransactionTemplate tx;

    public ConsumerExchangeService(ConsumerCertificateExchangeStore exchangeStore,
                                   CloudEventCodec codec,
                                   ProcessedEventStore eventStore,
                                   CertificateRetriever providerClient,
                                   CertificateRequester requestClient,
                                   AcceptanceReporter acceptanceClient,
                                   ParticipantContextStore contextStore,
                                   ConsumerCatalogService catalog,
                                   List<InboundNotificationListener> listeners,
                                   PlatformTransactionManager txManager) {
        this.exchangeStore = exchangeStore;
        this.codec = codec;
        this.eventStore = eventStore;
        this.providerClient = providerClient;
        this.requestClient = requestClient;
        this.acceptanceClient = acceptanceClient;
        this.contextStore = contextStore;
        this.catalog = catalog;
        this.listeners = listeners;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Consumer-initiated pull (CX-0135 &sect;3.3.1): opens a certificate request on the provider and records
     * the resulting exchange (mirroring the returned fulfillment status). The consumer never decides
     * acceptance itself — once an exchange is {@code FULFILLED}, a client drives {@code retrieve} + {@code accept}.
     */
    // Non-transactional: the remote request runs between short store transactions (context lookup, then the
    // exchange save) so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConsumerCertificateExchange initiateRequest(String contextId,
                                                       String providerBpn,
                                                       String providerDid,
                                                       String certificateType,
                                                       String flowId,
                                                       List<String> certifiedLocations) {
        var sender = requireContext(contextId);
        var call = new OutboundCall(sender, providerBpn, providerDid, flowId);
        ProviderRequestResult result;
        try {
            result = requestClient.request(certificateType, certifiedLocations, call);
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Provider request failed: " + e.getMessage());
        }
        var exchange = new ConsumerCertificateExchange(result.exchangeId(),
                result.certificateId(),
                result.revision(),
                true,
                result.status(),
                result.errors(),
                contextId,
                providerBpn,
                providerDid);
        exchangeStore.save(exchange);
        return exchange;
    }

    /** Polls the provider for the latest fulfillment status of a consumer-initiated request (CX-0135 &sect;3.3.1.1). */
    // Non-transactional: load the exchange, poll the provider outside any transaction, then save the mirrored
    // status — so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ConsumerCertificateExchange pollRequest(String contextId, String exchangeId, String flowId) {
        var exchange = findConsumerRequest(contextId, exchangeId);
        try {
            var result = requestClient.pollStatus(exchangeId, outboundCall(exchange, flowId));
            exchange.updateFulfillment(result.status(), result.certificateId(), result.errors());
            exchangeStore.save(exchange);
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
                .filter(ConsumerCertificateExchange::consumerInitiated)
                .filter(e -> contextId.equals(e.participantContextId()))
                .orElseThrow(() -> ApiException.notFound("Unknown request exchangeId: " + exchangeId));
    }

    /**
     * Handles one or more inbound notification CloudEvents (CX-0135 &sect;3.2.1 / &sect;3.2.2). The whole
     * batch runs in one transaction: every event is validated before any is applied, then each is claimed
     * and applied. Because dedup and every side effect share the transaction, a failure anywhere rolls the
     * batch back whole — including the dedup markers — so a retry re-applies all of it and nothing is lost
     * or double-applied. Duplicate events (same {@code source}+{@code id}) are skipped by the dedup claim.
     */
    @Transactional
    public void handleNotifications(byte[] body, VerifiedRequestContext requestContext) {
        var pending = new ArrayList<EventBatch.PendingEvent>();
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            switch (type) {
                case CcmEvents.TYPE_LIFECYCLE_STATUS -> {
                    // Decode the v3 wire payload and map the certificate back to the neutral domain.
                    var event = codec.decode(node, Ccm300LifecycleStatus.class);
                    var data = toDomainLifecycle(event.data());
                    validateLifecycle(data);
                    pending.add(new EventBatch.PendingEvent(codec.dedupKey(event), () -> applyLifecycle(data, requestContext)));
                }
                case CcmEvents.TYPE_FULFILLMENT_STATUS -> {
                    var event = codec.decode(node, FulfillmentStatusData.class);
                    validateFulfillment(event.data());
                    pending.add(new EventBatch.PendingEvent(codec.dedupKey(event), () -> applyFulfillment(event.data(), requestContext)));
                }
                default -> throw ApiException.badRequest("Unsupported notification event type: " + type);
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
    private void emit(InboundCcmEvent event) {
        afterCommit(() -> notifyListeners(event));
    }

    private void notifyListeners(InboundCcmEvent event) {
        for (var listener : listeners) {
            try {
                listener.onNotification(event);
            } catch (RuntimeException e) {
                LOG.warn("Inbound notification listener failed for exchange {}: {}", event.exchangeId(), e.getMessage());
            }
        }
    }

    /**
     * Returns the consumer's acceptance decision for an exchange (CX-0135 &sect;3.2.3). Returns
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
                exchange.certificateId(),
                exchange.revision(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    /**
     * Consumer-side reconciliation query: the exchanges awaiting the caller's action — {@code FULFILLED} but
     * not yet accepted (outstanding retrieve/accept), or accepted but not confirmed reported (needs
     * re-reporting). The safety net for a dropped notification callback or a lost acceptance report. Pass
     * {@code awaitingAcceptanceOnly=false} for all of the tenant's exchanges. Scoped and bounded in the
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
     * client reacting to an {@link InboundCcmEvent}.
     */
    // Non-transactional: load the exchange in its own short transaction, then fetch from the provider outside
    // any transaction so no DB connection is held across the outbound call.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RetrievedCertificate retrieve(String contextId, String exchangeId, String flowId) {
        var exchange = requireOwnedExchange(contextId, exchangeId);
        // An embedded push delivered the content inline (no pull endpoint to re-fetch); return what we kept.
        if (exchange.embeddedContent() != null) {
            return exchange.embeddedContent();
        }
        if (exchange.certificateId() == null) {
            throw ApiException.conflict("Exchange " + exchangeId + " has no certificate to retrieve yet");
        }
        try {
            return providerClient.fetch(exchange.certificateId(), outboundCall(exchange, flowId));
        } catch (IOException e) {
            throw new ApiException(BAD_GATEWAY, "Retrieval failed: " + e.getMessage());
        }
    }

    /**
     * Management-driven acceptance: records the caller's decision for an exchange and reports it to the
     * provider over the given live {@code flowId}. The decision (ACCEPTED / REJECTED / ERRORED) is the
     * caller's — the consumer applies no rule of its own.
     *
     * <p>The report is <b>best-effort</b> (post-commit), so a lost report leaves the acceptance recorded here
     * but unknown to the provider. Recovery is by polling, not a durable outbox: {@link #queryExchanges}
     * surfaces such exchanges for a re-drive, and the provider can pull the verdict via its
     * {@code poll-acceptance} op ({@code GET /certificate-acceptance-status/{id}}).
     */
    public void accept(String contextId, String exchangeId, AcceptanceStatus status,
                       List<StatusError> errors, String flowId) {
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
        // on success mark it reported (in its own transaction) so the reconciliation query stops surfacing it.
        afterCommit(() -> {
            acceptanceClient.report(exchangeId, exchange.certificateId(), status, call, errors);
            tx.executeWithoutResult(s -> exchangeStore.findById(exchangeId)
                    .ifPresent(ConsumerCertificateExchange::markAcceptanceReported));
        });
    }

    /**
     * Receives a certificate pushed by an external provider through a protocol adapter (e.g. a v2.4.0
     * push), as an embedded-document {@code CREATED}: records the lifecycle view + the inline content on the
     * exchange and emits the event, for a client to later {@code retrieve} (returning the inline content) and
     * {@code accept}. The {@code exchangeId} is supplied by the adapter — a consumer-local surrogate, since a
     * source protocol without an exchange concept cannot provide the provider-assigned one.
     */
    @Transactional
    public void receivePushedCertificate(String exchangeId, CertificateRecord certificate, VerifiedRequestContext requestContext) {
        applyLifecycle(new LifecycleStatusData(LifecycleStatus.CREATED, exchangeId, certificate), requestContext);
    }

    private static LifecycleStatusData toDomainLifecycle(Ccm300LifecycleStatus wire) {
        return new LifecycleStatusData(wire.status(), wire.exchangeId(), Ccm300CertificateCodec.toDomain(wire.certificate()));
    }

    private void validateLifecycle(LifecycleStatusData data) {
        if (data == null || data.status() == null || data.certificate() == null
                || data.certificate().certificateId() == null) {
            throw ApiException.badRequest("Lifecycle event is missing status or certificate.certificateId");
        }
        if (data.status() == LifecycleStatus.CREATED && data.exchangeId() == null) {
            throw ApiException.badRequest("A CREATED lifecycle event must carry data.exchangeId");
        }
    }

    private void applyLifecycle(LifecycleStatusData data, VerifiedRequestContext requestContext) {
        // Keep the consumer's known-certificate view in sync (CREATED/MODIFIED/WITHDRAWN), owned by the
        // receiving tenant (the verified audience).
        catalog.recordKnownCertificate(data, requestContext.participantContextId());
        var cert = data.certificate();

        if (data.status() == LifecycleStatus.CREATED) {
            // Record the consumer-side exchange now so it can be retrieved/accepted later. It is stamped with
            // the receiving tenant (the verified audience) and the provider (the verified caller) so a later
            // management-driven retrieve/accept can address the right provider on behalf of the right tenant.
            var exchange = ensureExchange(data.exchangeId(), cert.certificateId(), cert.revision(),
                    requestContext.participantContextId(), requestContext.bpnOrSubject(),
                    requestContext.subject());
            if (cert.hasEmbeddedContent()) {
                exchange.attachEmbeddedContent(embeddedToRetrieved(cert));
                exchangeStore.save(exchange);
            }
        }

        // Record + emit only. A provider-initiated push has no live flowId, so the consumer never reacts
        // inline; a plugged-in client drives retrieve/accept via the management API with its own flowId.
        emit(new InboundCcmEvent(InboundCcmEvent.Kind.LIFECYCLE, data.exchangeId(), cert.certificateId(),
                cert.revision(), data.status().name(), requestContext.bpnOrSubject()));
    }

    private ConsumerCertificateExchange ensureExchange(String exchangeId, String certificateId, Integer revision,
                                                       String contextId, String providerBpn, String providerDid) {
        // Runs inside the caller's transaction; a concurrent insert of the same id fails the primary-key
        // constraint (that transaction rolls back and retries), so no JVM lock is needed.
        return exchangeStore.findById(exchangeId).map(existing -> {
            // A reused exchangeId must belong to this tenant AND the calling provider (verified DID); otherwise
            // a different caller is addressing — and would overwrite — an exchange that is not theirs.
            if (!contextId.equals(existing.participantContextId())
                    || !providerDid.equals(existing.providerDid())) {
                throw ApiException.notFound("Unknown exchangeId: " + exchangeId);
            }
            return existing;
        }).orElseGet(() -> {
            var created = new ConsumerCertificateExchange(exchangeId, certificateId, revision != null ? revision : 1,
                    false, FulfillmentStatus.FULFILLED, null, contextId, providerBpn, providerDid);
            exchangeStore.save(created);
            return created;
        });
    }

    /** Builds a {@link RetrievedCertificate} from an embedded push's inline content (decoding each document). */
    private static RetrievedCertificate embeddedToRetrieved(CertificateRecord cert) {
        var documents = cert.documents().stream()
                .map(d -> new RetrievedDocument(d.documentId(), d.mediaType(),
                        d.contentBase64() == null ? new byte[0] : Base64.getDecoder().decode(d.contentBase64())))
                .toList();
        return new RetrievedCertificate(cert, documents);
    }

    private void validateFulfillment(FulfillmentStatusData data) {
        if (data == null || data.exchangeId() == null || data.status() == null) {
            throw ApiException.badRequest("Fulfillment event is missing exchangeId or status");
        }
        var hasErrors = data.errors() != null && !data.errors().isEmpty();
        if (data.status().isTerminal() && data.status() != FulfillmentStatus.FULFILLED && !hasErrors) {
            throw ApiException.badRequest("Status " + data.status() + " requires a non-empty 'errors' array");
        }
    }

    /**
     * A pushed Fulfillment status for a consumer-initiated exchange (CX-0135 &sect;3.2.2). Correlates by
     * {@code exchangeId} to a tracked request and mirrors the status, then records + emits — the consumer
     * never decides acceptance itself. An event for an exchange the consumer didn't open is ignored.
     */
    private void applyFulfillment(FulfillmentStatusData data, VerifiedRequestContext requestContext) {
        // Only a fulfillment for an exchange owned by the receiving tenant (the verified audience) AND opened
        // with the calling provider (the verified caller's DID) is applied — so a rogue provider cannot
        // overwrite another provider's exchange within this tenant.
        var contextId = requestContext.participantContextId();
        var callerDid = requestContext.subject();
        var exchange = exchangeStore.findById(data.exchangeId())
                .filter(e -> contextId.equals(e.participantContextId()))
                .filter(e -> callerDid.equals(e.providerDid()))
                .orElse(null);
        if (exchange == null) {
            LOG.info("Fulfillment status {} for unknown exchange {} — ignored", data.status(), data.exchangeId());
            return;
        }
        exchange.updateFulfillment(data.status(), data.certificateId(), data.errors());
        exchangeStore.save(exchange);
        // Record + emit only; a plugged-in client drives retrieve/accept via management with its own flowId.
        emit(new InboundCcmEvent(InboundCcmEvent.Kind.FULFILLMENT, exchange.exchangeId(), exchange.certificateId(),
                exchange.revision(), data.status().name(), requestContext.bpnOrSubject()));
    }

    /** An exchange that must exist within the tenant's scope, else 404 (existence not revealed across tenants). */
    private ConsumerCertificateExchange requireOwnedExchange(String contextId, String exchangeId) {
        return exchangeStore.findById(exchangeId)
                .filter(e -> contextId.equals(e.participantContextId()))
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
    }

    /** Builds an {@link OutboundCall} to the exchange's provider on behalf of the exchange's own tenant. */
    private OutboundCall outboundCall(ConsumerCertificateExchange exchange, String flowId) {
        return new OutboundCall(requireContext(exchange.participantContextId()),
                exchange.providerBpn(), exchange.providerDid(), flowId);
    }

    /** Resolves a participant context, failing the request when the named tenant does not exist. */
    private ParticipantContext requireContext(String contextId) {
        if (contextId == null) {
            throw ApiException.badRequest("A contextId is required");
        }
        return contextStore.find(contextId)
                .orElseThrow(() -> ApiException.badRequest("Unknown contextId: " + contextId));
    }
}
