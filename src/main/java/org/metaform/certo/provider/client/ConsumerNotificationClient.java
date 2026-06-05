package org.metaform.certo.provider.client;

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
 * <p>The consumer base URL is hardcoded via configuration ({@code certo.consumer-base-url}); there is
 * no DSP catalog lookup (out of scope).
 */
@Component
public class ConsumerNotificationClient {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerNotificationClient.class);
    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper;
    private final CertoProperties properties;
    private final Clock clock;

    public ConsumerNotificationClient(ObjectMapper mapper, CertoProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sends a {@code CertificateLifecycleStatus} CloudEvent to the consumer (CX-0135 &sect;4.3.1).
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    public boolean notifyLifecycle(LifecycleStatusData data) {
        var event = event(CcmEvents.TYPE_LIFECYCLE_STATUS, CcmEvents.SCHEMA_LIFECYCLE_STATUS, data);
        return post(event, data.exchangeId(), data.status() + " certificate " + data.certificateId());
    }

    /**
     * Pushes a {@code CertificateFulfillmentStatus} CloudEvent to the consumer for a consumer-initiated
     * exchange (CX-0135 &sect;4.3.2) — the push counterpart of the consumer polling fulfillment status.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    public boolean notifyFulfillment(FulfillmentStatusData data) {
        var event = event(CcmEvents.TYPE_FULFILLMENT_STATUS, CcmEvents.SCHEMA_FULFILLMENT_STATUS, data);
        return post(event, data.exchangeId(), "fulfillment " + data.status());
    }

    private <T> CloudEvent<T> event(String type, String dataSchema, T data) {
        return new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                type,
                properties.provider().source(),
                properties.consumer().bpn(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                dataSchema,
                properties.provider().bpn(),
                data);
    }

    private boolean post(CloudEvent<?> event, String exchangeId, String description) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize event for exchange {}: {}", exchangeId, e.getMessage());
            return false;
        }

        var base = HttpUrl.parse(properties.consumerBaseUrl());
        if (base == null) {
            LOG.warn("Invalid consumer base URL '{}'; not notifying", properties.consumerBaseUrl());
            return false;
        }
        var url = base.newBuilder().addPathSegment("certificate-notifications").build();

        var request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, CLOUDEVENTS_JSON))
                .build();

        LOG.info("Notifying consumer of {} (exchange {}) at {}", description, exchangeId, url);
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
