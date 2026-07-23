package org.metaform.certo.protocol.ccm300.provider;

import okhttp3.MediaType;
import org.metaform.certo.common.OutboundJsonClient;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolNotifier;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec;
import org.metaform.certo.protocol.ccm300.model.Ccm300LifecycleStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pushes certificate lifecycle events to a Certificate Consumer's notification API
 * ({@code POST /certificate-notifications}, CX-0135 &sect;4.3.1). Used by the provider's publish action to
 * notify a consumer that a certificate is available.
 *
 * <p>The event subject is the counterparty consumer's BPN (from the {@link OutboundCall}); the token and
 * endpoint are resolved from the siglet cache via the call's flow, and the POST is delegated to
 * {@link OutboundJsonClient}. This adapter does not read the {@link ExchangeBinding} (a native v3 target has none).
 */
@Component
public class Ccm300Notifier implements ProtocolNotifier {

    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OutboundJsonClient outbound;
    private final OutboundTokens outboundTokens;
    private final Clock clock;

    public Ccm300Notifier(OutboundJsonClient outbound, OutboundTokens outboundTokens, Clock clock) {
        this.outbound = outbound;
        this.outboundTokens = outboundTokens;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_3_0_0;
    }

    /**
     * Sends a {@code CertificateLifecycleStatus} CloudEvent to the consumer (CX-0135 &sect;4.3.1).
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    @Override
    public boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data, OutboundCall call) {
        // Token + counterparty endpoint from the siglet cache (scoped to the counterparty, keyed by the flow).
        var resolved = outboundTokens.forCall(call);
        // Render the neutral domain event to the v3 wire payload (the certificate goes through the codec).
        var wire = new Ccm300LifecycleStatus(data.status(), data.exchangeId(),
                Ccm300CertificateCodec.toWire(data.certificate()));
        var event = event(CcmEvents.TYPE_LIFECYCLE_STATUS, CcmEvents.SCHEMA_LIFECYCLE_STATUS, wire,
                call.sender(), call.counterpartyBpn());
        var certificateId = data.certificate() == null ? null : data.certificate().certificateId();
        return outbound.postTo(resolved.baseUrl(), "certificate-notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "notify " + data.status() + " certificate " + certificateId
                        + " for exchange " + data.exchangeId());
    }

    /**
     * Pushes a {@code CertificateFulfillmentStatus} CloudEvent to the consumer for a consumer-initiated
     * exchange (CX-0135 &sect;4.3.2) — the push counterpart of the consumer polling fulfillment status.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call) {
        // Token + counterparty endpoint from the siglet cache (keyed by the flow).
        var resolved = outboundTokens.forCall(call);
        var event = event(CcmEvents.TYPE_FULFILLMENT_STATUS, CcmEvents.SCHEMA_FULFILLMENT_STATUS, data,
                call.sender(), call.counterpartyBpn());
        return outbound.postTo(resolved.baseUrl(), "certificate-notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "notify fulfillment " + data.status() + " for exchange " + data.exchangeId());
    }

    private <T> CloudEvent<T> event(String type, String dataSchema, T data, ParticipantContext sender, String subjectBpn) {
        return new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                type,
                sender.source(),
                subjectBpn,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                dataSchema,
                sender.bpn(),
                data);
    }
}
