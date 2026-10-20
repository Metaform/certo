package org.metaform.certo.protocol.sce.provider;

import okhttp3.MediaType;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolNotifier;
import org.metaform.certo.protocol.sce.Sce;
import org.metaform.certo.protocol.sce.model.SceLifecycleStatus;
import org.metaform.certo.protocol.sce.model.SceFulfillmentStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.metaform.certo.protocol.sce.Sce.TYPE_FULFILLMENT_STATUS;
import static org.metaform.certo.protocol.sce.Sce.TYPE_LIFECYCLE_STATUS;
import static org.metaform.certo.protocol.sce.SceCertificateCodec.toWire;

/**
 * Pushes certificate lifecycle events to a Certificate Consumer's notification API
 * ({@code POST /notifications}, SCE Consumer API). Used by the provider's single-target
 * lifecycle notify to inform a consumer of a certificate lifecycle transition.
 *
 * <p>The event {@code subject} follows SCE (certificateId for lifecycle, exchangeId for fulfillment); the token and
 * endpoint are resolved from the siglet cache via the call's flow, and the POST is delegated to
 * {@link OutboundJsonClient}. This adapter does not read the {@link ExchangeBinding} (a transient notify target has none).
 */
@Component
public class SceNotifier implements ProtocolNotifier {

    @Override
    public String protocolId() {
        return Sce.PROTOCOL_ID;
    }


    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CloudEvent.CONTENT_TYPE_CLOUDEVENTS);

    private final OutboundJsonClient outbound;
    private final OutboundTokenCache outboundTokenCache;
    private final Clock clock;

    public SceNotifier(OutboundJsonClient outbound, OutboundTokenCache outboundTokenCache, Clock clock) {
        this.outbound = outbound;
        this.outboundTokenCache = outboundTokenCache;
        this.clock = clock;
    }

    /**
     * Sends a {@code CertificateLifecycleStatus} CloudEvent to the consumer. The
     * caller-supplied {@code messageId} is used verbatim as the event {@code id}: a retry that reuses it
     * repeats the same {@code source}+{@code id} — as the CloudEvents duplicate rules require — so the
     * consumer's dedup absorbs the re-send.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    @Override
    public boolean notifyLifecycle(ExchangeBinding binding, String messageId, LifecycleStatusData data, OutboundCall call) {
        // Token + counterparty endpoint from the siglet cache (scoped to the counterparty, keyed by the flow).
        var resolved = outboundTokenCache.forCall(call);
        // Render the neutral domain event to the SCE wire payload (the certificate goes through the codec).
        var wire = new SceLifecycleStatus(data.status(), toWire(data.certificate()));
        var certificateId = data.certificate() == null ? null : data.certificate().certificateId();
        // SCE: the subject of a lifecycle event is the certificateId.
        var event = event(TYPE_LIFECYCLE_STATUS, messageId, wire, call.sender(), certificateId);
        return outbound.postTo(resolved.baseUrl(), "notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "notify " + data.status() + " certificate " + certificateId);
    }

    /**
     * Pushes a {@code CertificateFulfillmentStatus} CloudEvent to the consumer for a consumer-initiated
     * exchange — the push counterpart of the consumer polling fulfillment status.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call) {
        // Token + counterparty endpoint from the siglet cache (keyed by the flow).
        var resolved = outboundTokenCache.forCall(call);
        // Render the neutral domain event to the SCE wire payload (the certificate goes through the codec).
        var wire = new SceFulfillmentStatus(data.exchangeId(), data.status(), toWire(data.certificate()), data.errors());
        // SCE: the subject of a fulfillment event is the exchangeId.
        var event = event(TYPE_FULFILLMENT_STATUS, UUID.randomUUID().toString(),
                wire, call.sender(), data.exchangeId());
        return outbound.postTo(resolved.baseUrl(), "notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "notify fulfillment " + data.status() + " for exchange " + data.exchangeId());
    }

    private <T> CloudEvent<T> event(String type, String eventId, T data,
                                    ParticipantContext sender, String subject) {
        return new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                type,
                // The source is the asserting participant's identity (its DID) — the recipient verifies it
                // against the authenticated sender (SCE sender-identity rule).
                sender.did(),
                subject,
                eventId,
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                null,
                // Optional core-messaging extension: the dataspace-assigned identifier, when one exists.
                sender.dataspaceIdentifier(),
                data);
    }
}
