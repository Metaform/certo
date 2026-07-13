package org.metaform.certo.protocol.ccm300.provider;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolNotifier;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec;
import org.metaform.certo.protocol.ccm300.model.Ccm300LifecycleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pushes certificate lifecycle events to a Certificate Consumer's notification API
 * ({@code POST /certificate-notifications}, CX-0135 &sect;4.3.1) using OkHttp. Used by the provider's
 * publish action to notify a consumer that a certificate is available.
 *
 * <p>The target consumer is taken from the exchange's {@link ExchangeBinding} — its BPN is the event
 * subject and its {@code callbackUrl} the endpoint — exactly as the v2.4.0 notifier routes. The
 * configured {@code certo.consumer.*} values are this runtime's own consumer identity, never the push
 * target. (A consumer-initiated fulfillment push has no per-exchange target in the current protocol, so
 * that path alone falls back to the configured consumer endpoint.)
 */
@Component
public class Ccm300Notifier implements ProtocolNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm300Notifier.class);
    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final CertoProperties properties;
    private final Clock clock;

    public Ccm300Notifier(OkHttpClient httpClient, ObjectMapper mapper, CertoProperties properties, Clock clock) {
        this.http = httpClient;
        this.mapper = mapper;
        this.properties = properties;
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
    public boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data) {
        if (binding == null || binding.callbackUrl() == null) {
            LOG.warn("No consumer callback URL for the target; cannot deliver {}", data.status());
            return false;
        }
        // Render the neutral domain event to the v3 wire payload (the certificate goes through the codec).
        var wire = new Ccm300LifecycleStatus(data.status(), data.exchangeId(),
                Ccm300CertificateCodec.toWire(data.certificate()));
        var event = event(CcmEvents.TYPE_LIFECYCLE_STATUS, CcmEvents.SCHEMA_LIFECYCLE_STATUS, wire, binding.peerBpn());
        var certificateId = data.certificate() == null ? null : data.certificate().certificateId();
        return post(event, binding.callbackUrl(), data.exchangeId(), data.status() + " certificate " + certificateId);
    }

    /**
     * Pushes a {@code CertificateFulfillmentStatus} CloudEvent to the consumer for a consumer-initiated
     * exchange (CX-0135 &sect;4.3.2) — the push counterpart of the consumer polling fulfillment status.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data) {
        // A consumer-initiated pull records no binding (the CX-0135 §3.3.1 request carries no callback), so the
        // target falls back to the configured consumer endpoint; an explicit binding, when present, wins.
        var subject = binding != null && binding.peerBpn() != null ? binding.peerBpn() : properties.consumer().bpn();
        var base = binding != null && binding.callbackUrl() != null ? binding.callbackUrl() : properties.consumerBaseUrl();
        var event = event(CcmEvents.TYPE_FULFILLMENT_STATUS, CcmEvents.SCHEMA_FULFILLMENT_STATUS, data, subject);
        return post(event, base, data.exchangeId(), "fulfillment " + data.status());
    }

    private <T> CloudEvent<T> event(String type, String dataSchema, T data, String subjectBpn) {
        return new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                type,
                properties.provider().source(),
                subjectBpn,
                UUID.randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                dataSchema,
                properties.provider().bpn(),
                data);
    }

    private boolean post(CloudEvent<?> event, String baseUrl, String exchangeId, String description) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize event for exchange {}: {}", exchangeId, e.getMessage());
            return false;
        }

        var base = HttpUrl.parse(baseUrl);
        if (base == null) {
            LOG.warn("Invalid consumer callback URL '{}'; not notifying", baseUrl);
            return false;
        }
        var url = base.newBuilder().addPathSegment("certificate-notifications").build();

        var request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, CLOUDEVENTS_JSON))
                .build();

        try (var response = http.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            LOG.warn("Consumer rejected notification for exchange {}: HTTP {}", exchangeId, response.code());
            return false;
        } catch (IOException e) {
            LOG.warn("Failed to notify consumer for exchange {}: {}", exchangeId, e.getMessage());
            return false;
        }
    }
}
