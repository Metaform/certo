package org.metaform.certo.consumer;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.api.dto.CertificateAcceptanceStatusResponse;
import org.metaform.certo.consumer.client.ProviderAcceptanceClient;
import org.metaform.certo.consumer.client.ProviderCertificateClient;
import org.metaform.certo.consumer.client.ProviderRequestClient;
import org.metaform.certo.consumer.client.ProviderRequestResult;
import org.metaform.certo.consumer.client.RetrievedCertificate;
import org.metaform.certo.consumer.model.ConsumerCertificateExchange;
import org.metaform.certo.consumer.model.KnownCertificate;
import org.metaform.certo.consumer.store.ConsumerCertificateStore;
import org.metaform.certo.consumer.store.ConsumerCertificateExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the Certificate Consumer API behaviour (CX-0135 v3, &sect;3.2): receiving lifecycle and
 * fulfillment CloudEvents from providers, and exposing the consumer's acceptance decision for an
 * exchange.
 *
 * <p>This consumer uses the push-pull mechanism (Option 2 of the v2&rarr;v3 migration): a lifecycle
 * {@code CREATED} notification carries only the light-triage certificate subset, and the consumer then
 * pulls the full metadata and each document binary from the provider data plane via
 * {@link ProviderCertificateClient} (no embedded {@code contentBase64}). It evaluates the certificate —
 * {@code ACCEPTED} if a document is present and the certificate is within its validity window,
 * {@code REJECTED} if expired, {@code ERRORED} if it cannot be retrieved or has no document — and
 * reports the terminal outcome back to the provider, closing the exchange loop. Reporting {@code RETRIEVED}
 * is optional and skipped (CX-0135 &sect;2.1.3).
 */
@Service
public class ConsumerCertificateService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerCertificateService.class);

    private final ConsumerCertificateExchangeStore exchanges;
    private final ConsumerCertificateStore knownCertificates;
    private final CloudEventCodec codec;
    private final ProcessedEventStore processedEvents;
    private final ProviderCertificateClient providerClient;
    private final ProviderRequestClient requestClient;
    private final ProviderAcceptanceClient acceptanceClient;
    private final Clock clock;

    public ConsumerCertificateService(ConsumerCertificateExchangeStore exchanges, ConsumerCertificateStore knownCertificates,
                                      CloudEventCodec codec, ProcessedEventStore processedEvents,
                                      ProviderCertificateClient providerClient, ProviderRequestClient requestClient,
                                      ProviderAcceptanceClient acceptanceClient, Clock clock) {
        this.exchanges = exchanges;
        this.knownCertificates = knownCertificates;
        this.codec = codec;
        this.processedEvents = processedEvents;
        this.providerClient = providerClient;
        this.requestClient = requestClient;
        this.acceptanceClient = acceptanceClient;
        this.clock = clock;
    }

    /**
     * Consumer-initiated pull (CX-0135 &sect;3.3.1): opens a certificate request on the provider, records
     * the resulting exchange, and — if the provider already returned {@code FULFILLED} — retrieves and
     * accepts immediately. Otherwise the consumer waits for a fulfillment push (or a poll).
     */
    public ConsumerCertificateExchange initiateRequest(String certificateType, List<String> certifiedLocationBpns) {
        ProviderRequestResult result;
        try {
            result = requestClient.request(certificateType, certifiedLocationBpns);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider request failed: " + e.getMessage());
        }
        var exchange = new ConsumerCertificateExchange(result.exchangeId(), result.certificateId(), result.revision(),
                true, result.status(), result.errors());
        exchanges.save(exchange);
        LOG.info("Opened request -> exchange {} ({} r{}), fulfillment {}",
                result.exchangeId(), result.certificateId(), result.revision(), result.status());
        onFulfillmentStatus(exchange);
        return exchange;
    }

    /** Polls the provider for the latest fulfillment status of a consumer-initiated request (CX-0135 &sect;3.3.1.1). */
    public ConsumerCertificateExchange pollRequest(String exchangeId) {
        var exchange = findConsumerRequest(exchangeId);
        try {
            var result = requestClient.pollStatus(exchangeId);
            exchange.updateFulfillment(result.status(), result.errors());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider poll failed: " + e.getMessage());
        }
        onFulfillmentStatus(exchange);
        return exchange;
    }

    public ConsumerCertificateExchange getRequest(String exchangeId) {
        return findConsumerRequest(exchangeId);
    }

    private ConsumerCertificateExchange findConsumerRequest(String exchangeId) {
        return exchanges.find(exchangeId)
                .filter(ConsumerCertificateExchange::consumerInitiated)
                .orElseThrow(() -> ApiException.notFound("Unknown request exchangeId: " + exchangeId));
    }

    /**
     * Handles one or more inbound notification CloudEvents (CX-0135 &sect;3.2.1 / &sect;3.2.2). The batch
     * is atomic: every event is validated before any is applied. Duplicate events (same
     * {@code source}+{@code id}) are ignored.
     */
    public void handleNotifications(byte[] body) {
        var pending = new ArrayList<PendingEvent>();
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            switch (type) {
                case CcmEvents.TYPE_LIFECYCLE_STATUS -> {
                    var event = codec.decode(node, LifecycleStatusData.class);
                    validateLifecycle(event.data());
                    pending.add(new PendingEvent(codec.dedupKey(event), () -> applyLifecycle(event.data())));
                }
                case CcmEvents.TYPE_FULFILLMENT_STATUS -> {
                    var event = codec.decode(node, FulfillmentStatusData.class);
                    validateFulfillment(event.data());
                    pending.add(new PendingEvent(codec.dedupKey(event), () -> applyFulfillment(event.data())));
                }
                default -> throw ApiException.badRequest("Unsupported notification event type: " + type);
            }
        }
        for (var event : pending) {
            if (processedEvents.firstSeen(event.dedupKey())) {
                event.apply().run();
            } else {
                LOG.info("Ignoring duplicate event {}", event.dedupKey());
            }
        }
    }

    /**
     * Returns the consumer's acceptance decision for an exchange (CX-0135 &sect;3.2.3). Returns
     * {@code 404} until the Acceptance phase has begun (the exchange may still be in Fulfillment).
     */
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .filter(e -> e.acceptanceStatus() != null)
                .orElseThrow(() -> ApiException.notFound("No acceptance status for exchangeId: " + exchangeId));
        return new CertificateAcceptanceStatusResponse(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    /** Returns the consumer's lifecycle view of a certificate it has learned about (demo/inspection). */
    public KnownCertificate getKnownCertificate(String certificateId) {
        return knownCertificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
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

    private void applyLifecycle(LifecycleStatusData data) {
        // Keep the consumer's lifecycle view of the certificate in sync (CREATED/MODIFIED/WITHDRAWN).
        recordKnownCertificate(data);

        if (data.status() != LifecycleStatus.CREATED) {
            LOG.info("{} certificate {} (no exchange opened)", data.status(), data.certificate().certificateId());
            return;
        }
        // Provider-initiated push: pull the full certificate + documents, evaluate, and report.
        var cert = data.certificate();
        retrieveEvaluateAndReport(data.exchangeId(), cert.certificateId(), cert.revision());
    }

    /** Creates or updates the consumer's lifecycle view of the certificate from a lifecycle event. */
    private void recordKnownCertificate(LifecycleStatusData data) {
        CertificateRecord c = data.certificate();
        knownCertificates.find(c.certificateId()).ifPresentOrElse(
                known -> known.apply(c.revision(), data.status(), c.certificateType(),
                        c.validFrom(), c.validUntil(), c.certifiedLocations()),
                () -> knownCertificates.save(new KnownCertificate(c.certificateId(), c.revision(), data.status(),
                        c.certificateType(), c.validFrom(), c.validUntil(), c.certifiedLocations())));
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
     * {@code exchangeId} to a tracked request, mirrors the status, and on {@code FULFILLED} retrieves and
     * accepts. An event for an exchange the consumer didn't open is ignored.
     */
    private void applyFulfillment(FulfillmentStatusData data) {
        var exchange = exchanges.find(data.exchangeId()).orElse(null);
        if (exchange == null) {
            LOG.info("Fulfillment status {} for unknown exchange {} — ignored", data.status(), data.exchangeId());
            return;
        }
        exchange.updateFulfillment(data.status(), data.errors());
        LOG.info("Fulfillment status {} pushed for exchange {}", data.status(), data.exchangeId());
        onFulfillmentStatus(exchange);
    }

    /** When an exchange becomes FULFILLED (and acceptance hasn't started), retrieve and accept. */
    private void onFulfillmentStatus(ConsumerCertificateExchange exchange) {
        if (exchange.fulfillmentStatus() == FulfillmentStatus.FULFILLED && exchange.acceptanceStatus() == null) {
            retrieveEvaluateAndReport(exchange.exchangeId(), exchange.certificateId(), exchange.revision());
        }
    }

    /**
     * Pulls the certificate metadata and documents, evaluates them, and reports the terminal outcome
     * directly (RETRIEVED skipped) — shared by the provider-initiated push and the consumer-initiated
     * pull paths.
     */
    private void retrieveEvaluateAndReport(String exchangeId, String certificateId, Integer revision) {
        RetrievedCertificate certificate;
        try {
            certificate = providerClient.fetch(certificateId, revision);
        } catch (IOException e) {
            LOG.warn("Could not retrieve certificate {} r{}: {}", certificateId, revision, e.getMessage());
            recordAndReport(exchangeId, certificateId, revision, AcceptanceStatus.ERRORED,
                    List.of(new StatusError("Unable to retrieve certificate: " + e.getMessage())));
            return;
        }
        var decision = evaluateRetrieved(certificate);
        recordAndReport(exchangeId, certificateId, revision, decision.status(), decision.errors());
        LOG.info("Exchange {} (certificate {}) concluded {}", exchangeId, certificateId, decision.status());
    }

    /**
     * Records the acceptance status on the exchange and reports it to the provider, creating the exchange
     * first if needed. For a provider-initiated push there is no prior request, so the exchange is created
     * here entering directly at {@code FULFILLED}.
     */
    private ConsumerCertificateExchange recordAndReport(String exchangeId, String certificateId, Integer revision,
                                             AcceptanceStatus status, List<StatusError> errors) {
        var exchange = exchanges.find(exchangeId).orElseGet(() -> {
            var created = new ConsumerCertificateExchange(exchangeId, certificateId, revision != null ? revision : 1,
                    false, FulfillmentStatus.FULFILLED, null);
            exchanges.save(created);
            return created;
        });
        exchange.transitionAcceptance(status, errors);
        LOG.info("Exchange {} (certificate {}) -> {}", exchangeId, certificateId, status);
        acceptanceClient.report(exchangeId, certificateId, status, errors);
        return exchange;
    }

    /** Evaluates an already-retrieved certificate's content, yielding the terminal acceptance decision. */
    private AcceptanceDecision evaluateRetrieved(RetrievedCertificate certificate) {
        var hasDocument = certificate.documents().stream()
                .anyMatch(d -> d.content() != null && d.content().length > 0);
        if (!hasDocument) {
            return new AcceptanceDecision(AcceptanceStatus.ERRORED,
                    List.of(new StatusError("Certificate has no retrievable document")));
        }
        var validUntil = certificate.metadata().validUntil();
        if (validUntil != null && validUntil.isBefore(LocalDate.now(clock))) {
            return new AcceptanceDecision(AcceptanceStatus.REJECTED,
                    List.of(new StatusError("Certificate has expired")));
        }
        return new AcceptanceDecision(AcceptanceStatus.ACCEPTED, null);
    }

    private record AcceptanceDecision(AcceptanceStatus status, List<StatusError> errors) {
    }

    /** A validated event whose side effects are deferred until the whole batch has been validated. */
    private record PendingEvent(String dedupKey, Runnable apply) {
    }
}
