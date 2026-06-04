package org.metaform.certo.provider.client;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
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
     * Sends a {@code CertificateLifecycleStatus} CloudEvent to the consumer.
     *
     * @return {@code true} if the consumer accepted the event (2xx), {@code false} otherwise
     */
    public boolean notifyLifecycle(LifecycleStatusData data) {
        var event = new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                CcmEvents.TYPE_LIFECYCLE_STATUS,
                properties.provider().source(),
                properties.consumer().bpn(),
                UUID.randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                CcmEvents.SCHEMA_LIFECYCLE_STATUS,
                properties.provider().bpn(),
                data);

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize lifecycle event for exchange {}: {}", data.exchangeId(), e.getMessage());
            return false;
        }

        HttpUrl base = HttpUrl.parse(properties.consumerBaseUrl());
        if (base == null) {
            LOG.warn("Invalid consumer base URL '{}'; not notifying", properties.consumerBaseUrl());
            return false;
        }
        HttpUrl url = base.newBuilder().addPathSegment("certificate-notifications").build();

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, CLOUDEVENTS_JSON))
                .build();

        LOG.info("Notifying consumer of {} certificate {} (exchange {}) at {}",
                data.status(), data.certificateId(), data.exchangeId(), url);
        try (Response response = http.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            LOG.warn("Consumer rejected lifecycle notification for exchange {}: HTTP {}",
                    data.exchangeId(), response.code());
            return false;
        } catch (IOException e) {
            LOG.warn("Failed to notify consumer for exchange {}: {}", data.exchangeId(), e.getMessage());
            return false;
        }
    }
}
