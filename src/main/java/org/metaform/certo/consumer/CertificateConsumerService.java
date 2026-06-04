package org.metaform.certo.consumer;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
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
import org.metaform.certo.consumer.client.RetrievedCertificate;
import org.metaform.certo.consumer.model.ConsumerExchange;
import org.metaform.certo.consumer.store.ConsumerExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
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
public class CertificateConsumerService {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateConsumerService.class);

    private final ConsumerExchangeStore exchanges;
    private final CloudEventCodec codec;
    private final ProviderCertificateClient providerClient;
    private final ProviderAcceptanceClient acceptanceClient;
    private final Clock clock;

    public CertificateConsumerService(ConsumerExchangeStore exchanges, CloudEventCodec codec,
                                      ProviderCertificateClient providerClient,
                                      ProviderAcceptanceClient acceptanceClient, Clock clock) {
        this.exchanges = exchanges;
        this.codec = codec;
        this.providerClient = providerClient;
        this.acceptanceClient = acceptanceClient;
        this.clock = clock;
    }

    /** Handles one or more inbound notification CloudEvents (CX-0135 &sect;4.3.1 / &sect;4.3.2). */
    public void handleNotifications(byte[] body) {
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            switch (type) {
                case CcmEvents.TYPE_LIFECYCLE_STATUS ->
                        handleLifecycle(codec.decode(node, LifecycleStatusData.class).data());
                case CcmEvents.TYPE_FULFILLMENT_STATUS ->
                        handleFulfillment(codec.decode(node, FulfillmentStatusData.class).data());
                default -> throw ApiException.badRequest("Unsupported notification event type: " + type);
            }
        }
    }

    /** Returns the consumer's acceptance decision for an exchange (CX-0135 &sect;4.3.3). */
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return new CertificateAcceptanceStatusResponse(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    private void handleLifecycle(LifecycleStatusData data) {
        if (data == null || data.certificateId() == null || data.status() == null) {
            throw ApiException.badRequest("Lifecycle event is missing certificateId or status");
        }
        if (data.status() == LifecycleStatus.CREATED) {
            if (data.exchangeId() == null) {
                throw ApiException.badRequest("A CREATED lifecycle event must carry data.exchangeId");
            }
            var decision = evaluate(data);
            exchanges.save(new ConsumerExchange(
                    data.exchangeId(), data.certificateId(), decision.status(), decision.errors()));
            LOG.info("CREATED certificate {} v{} -> opened exchange {}, decided {}",
                    data.certificateId(), data.version(), data.exchangeId(), decision.status());
            // Close the loop: report the acceptance outcome back to the provider (best-effort).
            acceptanceClient.report(
                    data.exchangeId(), data.certificateId(), decision.status(), decision.errors());
        } else {
            // MODIFIED and WITHDRAWN do not open an exchange (CX-0135 §2.2.4); record for the demo log only.
            LOG.info("{} certificate {} v{} (no exchange opened)", data.status(), data.certificateId(), data.version());
        }
    }

    private void handleFulfillment(FulfillmentStatusData data) {
        if (data == null || data.exchangeId() == null || data.status() == null) {
            throw ApiException.badRequest("Fulfillment event is missing exchangeId or status");
        }
        var hasErrors = data.errors() != null && !data.errors().isEmpty();
        if (data.status().isTerminal() && data.status() != FulfillmentStatus.FULFILLED && !hasErrors) {
            throw ApiException.badRequest("Status " + data.status() + " requires a non-empty 'errors' array");
        }
        LOG.info("Fulfillment status {} for exchange {}", data.status(), data.exchangeId());
    }

    /**
     * Retrieves the certificate from the provider and evaluates it. Retrieval failures map to
     * {@code ERRORED} (a business error — the consumer could not validate the certificate).
     */
    private AcceptanceDecision evaluate(LifecycleStatusData data) {
        RetrievedCertificate certificate;
        try {
            certificate = providerClient.fetch(data.certificateId(), data.version());
        } catch (IOException e) {
            LOG.warn("Could not retrieve certificate {} v{}: {}",
                    data.certificateId(), data.version(), e.getMessage());
            return new AcceptanceDecision(AcceptanceStatus.ERRORED,
                    List.of(new StatusError("Unable to retrieve certificate: " + e.getMessage())));
        }

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
}
