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
import org.metaform.certo.consumer.model.ConsumerExchange;
import org.metaform.certo.consumer.store.ConsumerExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Implements the Certificate Consumer Notification API behaviour (CX-0135 &sect;4.3): receiving
 * lifecycle and fulfillment CloudEvents from providers, and exposing the consumer's acceptance
 * decision for an exchange.
 *
 * <p>Demo simplification: on a {@code CREATED} lifecycle event the consumer immediately simulates
 * retrieving and evaluating the certificate, deciding {@code ACCEPTED} unless the certificate is
 * already expired (then {@code REJECTED}). A real consumer would retrieve the PDF, run business
 * validation asynchronously, and also POST a {@code CertificateAcceptanceStatus} event back to the
 * provider.
 */
@Service
public class CertificateConsumerService {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateConsumerService.class);

    private final ConsumerExchangeStore exchanges;
    private final CloudEventCodec codec;
    private final Clock clock;

    public CertificateConsumerService(ConsumerExchangeStore exchanges, CloudEventCodec codec, Clock clock) {
        this.exchanges = exchanges;
        this.codec = codec;
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

    /** Simulates the consumer's evaluation of a newly created certificate. */
    private AcceptanceDecision evaluate(LifecycleStatusData data) {
        var today = LocalDate.now(clock);
        if (data.validUntil() != null && data.validUntil().isBefore(today)) {
            return new AcceptanceDecision(AcceptanceStatus.REJECTED,
                    List.of(new StatusError("Certificate has expired")));
        }
        return new AcceptanceDecision(AcceptanceStatus.ACCEPTED, null);
    }

    private record AcceptanceDecision(AcceptanceStatus status, List<StatusError> errors) {
    }
}
