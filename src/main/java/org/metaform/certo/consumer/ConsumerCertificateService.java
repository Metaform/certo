package org.metaform.certo.consumer;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
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
 * Implements the Certificate Consumer Notification API behaviour (CX-0135 &sect;4.3): receiving
 * lifecycle and fulfillment CloudEvents from providers, and exposing the consumer's acceptance
 * decision for an exchange.
 *
 * <p>On a {@code CREATED} lifecycle event the consumer actually retrieves the certificate from the
 * provider's data plane (via {@link ProviderCertificateClient}) and evaluates it: {@code ACCEPTED} if
 * the certificate is retrievable and within its validity window, {@code REJECTED} if it is expired, or
 * {@code ERRORED} if it cannot be retrieved or has no document. The decision is recorded locally (so a
 * provider can query it) and also reported back to the provider as a {@code CertificateAcceptanceStatus}
 * CloudEvent, closing the exchange loop. A fuller implementation would run validation asynchronously.
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
     * Consumer-initiated pull (CX-0135 &sect;4.4.1): opens a certificate request on the provider, records
     * the resulting exchange, and — if the provider already returned {@code FULFILLED} — retrieves and
     * accepts immediately. Otherwise the consumer waits for a fulfillment push (or a poll).
     */
    public ConsumerCertificateExchange initiateRequest(String certificateType, List<String> locationBpns) {
        ProviderRequestResult result;
        try {
            result = requestClient.request(certificateType, locationBpns);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Provider request failed: " + e.getMessage());
        }
        var exchange = new ConsumerCertificateExchange(result.exchangeId(), result.certificateId(), result.version(),
                true, result.status(), result.errors());
        exchanges.save(exchange);
        LOG.info("Opened request -> exchange {} ({} v{}), fulfillment {}",
                result.exchangeId(), result.certificateId(), result.version(), result.status());
        onFulfillmentStatus(exchange); // may retrieve+accept immediately if already FULFILLED
        return exchange;
    }

    /** Polls the provider for the latest fulfillment status of a consumer-initiated request (CX-0135 &sect;4.4.2). */
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

    /** Finds a consumer-initiated (pull) exchange; push exchanges aren't "requests". */
    private ConsumerCertificateExchange findConsumerRequest(String exchangeId) {
        return exchanges.find(exchangeId)
                .filter(ConsumerCertificateExchange::consumerInitiated)
                .orElseThrow(() -> ApiException.notFound("Unknown request exchangeId: " + exchangeId));
    }

    /**
     * Handles one or more inbound notification CloudEvents (CX-0135 &sect;4.3.1 / &sect;4.3.2). The batch
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
     * Returns the consumer's acceptance decision for an exchange (CX-0135 &sect;4.3.3). Returns
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
        if (data == null || data.certificateId() == null || data.status() == null) {
            throw ApiException.badRequest("Lifecycle event is missing certificateId or status");
        }
        if (data.status() == LifecycleStatus.CREATED && data.exchangeId() == null) {
            throw ApiException.badRequest("A CREATED lifecycle event must carry data.exchangeId");
        }
    }

    private void applyLifecycle(LifecycleStatusData data) {
        // Keep the consumer's lifecycle view of the certificate in sync (CREATED/MODIFIED/WITHDRAWN).
        recordKnownCertificate(data);

        if (data.status() != LifecycleStatus.CREATED) {
            // MODIFIED and WITHDRAWN do not open an exchange (CX-0135 §2.2.4); the consumer just notes the
            // new state. (A fuller consumer MAY retrieve the latest version after a MODIFIED.)
            LOG.info("{} certificate {} v{} (no exchange opened)", data.status(), data.certificateId(), data.version());
            return;
        }
        // Provider-initiated push: the certificate is available now — retrieve, evaluate, and report.
        retrieveEvaluateAndReport(data.exchangeId(), data.certificateId(), data.version());
    }

    /** Creates or updates the consumer's lifecycle view of the certificate from a lifecycle event. */
    private void recordKnownCertificate(LifecycleStatusData data) {
        var version = data.version() != null ? data.version() : 0;
        knownCertificates.find(data.certificateId()).ifPresentOrElse(
                known -> known.apply(version, data.status(), data.certificateType(),
                        data.validFrom(), data.validUntil(), data.locationBpns()),
                () -> knownCertificates.save(new KnownCertificate(data.certificateId(), version, data.status(),
                        data.certificateType(), data.validFrom(), data.validUntil(), data.locationBpns())));
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
     * A pushed Fulfillment status for a consumer-initiated exchange (CX-0135 &sect;4.3.2). Correlates by
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
            retrieveEvaluateAndReport(exchange.exchangeId(), exchange.certificateId(), exchange.version());
        }
    }

    /**
     * Retrieves the certificate, evaluates it, and reports the terminal outcome — shared by the
     * provider-initiated push (lifecycle CREATED) and the consumer-initiated pull paths.
     *
     * <p>Reporting the non-terminal {@code RETRIEVED} status is <em>optional</em> (CX-0135 &sect;2.1.3):
     * an exchange may transition straight from {@code FULFILLED} to a terminal acceptance state. This
     * consumer takes that shortcut — it concludes with a single acceptance callback rather than emitting
     * a separate {@code RETRIEVED} receipt first.
     */
    private void retrieveEvaluateAndReport(String exchangeId, String certificateId, Integer version) {
        RetrievedCertificate certificate;
        try {
            certificate = providerClient.fetch(certificateId, version);
        } catch (IOException e) {
            // Could not retrieve -> conclude directly at ERRORED.
            LOG.warn("Could not retrieve certificate {} v{}: {}", certificateId, version, e.getMessage());
            recordAndReport(exchangeId, certificateId, version, AcceptanceStatus.ERRORED,
                    List.of(new StatusError("Unable to retrieve certificate: " + e.getMessage())));
            return;
        }
        // Retrieved: evaluate and conclude directly at the terminal status (RETRIEVED is skipped).
        var decision = evaluateRetrieved(certificate);
        recordAndReport(exchangeId, certificateId, version, decision.status(), decision.errors());
        LOG.info("Exchange {} (certificate {}) concluded {}", exchangeId, certificateId, decision.status());
    }

    /**
     * Records the acceptance status on the exchange and reports it to the provider, creating the exchange
     * first if needed. For a provider-initiated push (lifecycle CREATED) there is no prior request, so the
     * exchange is created here entering directly at {@code FULFILLED}.
     */
    private ConsumerCertificateExchange recordAndReport(String exchangeId, String certificateId, Integer version,
                                             AcceptanceStatus status, List<StatusError> errors) {
        var exchange = exchanges.find(exchangeId).orElseGet(() -> {
            var created = new ConsumerCertificateExchange(exchangeId, certificateId, version != null ? version : 0,
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
        if (certificate.pdf() == null || certificate.pdf().length == 0) {
            return new AcceptanceDecision(AcceptanceStatus.ERRORED,
                    List.of(new StatusError("Certificate document is missing or empty")));
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
