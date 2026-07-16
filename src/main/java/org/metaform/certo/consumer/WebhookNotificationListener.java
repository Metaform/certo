package org.metaform.certo.consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.metaform.certo.consumer.spi.InboundCcmEvent;
import org.metaform.certo.consumer.spi.InboundNotificationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * An out-of-process flavour of the inbound extension point: when {@code certo.consumer.notification-callback-url}
 * is configured, each recorded inbound event is POSTed as JSON to that URL, fire-and-forget. A client
 * listening there decides what to do and drives the consumer management API (retrieve/accept) on its own
 * timeline, supplying its live {@code flowId}. Delivery is best-effort — a failure is logged, never
 * propagated (the event is already recorded and reconcilable).
 */
@Component
@ConditionalOnProperty(name = "certo.consumer.notification-callback-url")
public class WebhookNotificationListener implements InboundNotificationListener {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookNotificationListener.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String callbackUrl;

    public WebhookNotificationListener(OkHttpClient http, ObjectMapper mapper,
                                       @Value("${certo.consumer.notification-callback-url}") String callbackUrl) {
        this.http = http;
        this.mapper = mapper;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public void onNotification(InboundCcmEvent event) {
        try {
            var json = mapper.writeValueAsString(event);
            var request = new Request.Builder().url(callbackUrl).post(RequestBody.create(json, JSON)).build();
            try (var response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    LOG.warn("Notification callback {} returned HTTP {} for exchange {}",
                            callbackUrl, response.code(), event.exchangeId());
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to deliver notification callback to {} for exchange {}: {}",
                    callbackUrl, event.exchangeId(), e.getMessage());
        }
    }
}
