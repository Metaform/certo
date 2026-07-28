package org.metaform.certo.consumer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.metaform.certo.consumer.spi.InboundCcmEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The optional out-of-process inbound extension point: when {@code certo.consumer.notification-callback-url}
 * is set, {@link WebhookNotificationListener} is registered and POSTs each recorded inbound event to that URL.
 * Delivery is best-effort — a webhook failure is logged, never propagated (the event is already recorded and
 * reconcilable). Retries are disabled here so the failure case fails fast.
 */
@SpringBootTest(properties = "certo.http.max-retries=0")
class WebhookNotificationListenerTest {

    private static final MockWebServer WEBHOOK = new MockWebServer();

    @DynamicPropertySource
    static void callbackUrl(DynamicPropertyRegistry registry) throws IOException {
        WEBHOOK.start();
        registry.add("certo.consumer.notification-callback-url", () -> WEBHOOK.url("/callback").toString());
    }

    @AfterAll
    static void stop() throws IOException {
        WEBHOOK.shutdown();
    }

    @Autowired(required = false)
    WebhookNotificationListener listener;

    @Autowired
    ObjectMapper mapper;

    @Test
    void isRegisteredWhenTheCallbackUrlIsConfigured() {
        assertThat(listener).as("@ConditionalOnProperty should create the listener when the URL is set").isNotNull();
    }

    @Test
    void postsTheRecordedEventToTheConfiguredWebhook() throws Exception {
        WEBHOOK.enqueue(new MockResponse().setResponseCode(200));
        var event = new InboundCcmEvent(InboundCcmEvent.Kind.LIFECYCLE, "exch-wh-1", "cert-wh-1", 2, "MODIFIED", "BPNL-provider");

        listener.onNotification(event);

        RecordedRequest request = WEBHOOK.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/callback");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        var body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("exchangeId").asString()).isEqualTo("exch-wh-1");
        assertThat(body.get("certificateId").asString()).isEqualTo("cert-wh-1");
        assertThat(body.get("status").asString()).isEqualTo("MODIFIED");
    }

    @Test
    void aWebhookFailureIsSwallowed() throws Exception {
        // The webhook rejects the delivery — onNotification must not throw (the event is already recorded).
        WEBHOOK.enqueue(new MockResponse().setResponseCode(500));
        var event = new InboundCcmEvent(InboundCcmEvent.Kind.FULFILLMENT, "exch-wh-2", "cert-wh-2", null, "FULFILLED", null);

        assertThatCode(() -> listener.onNotification(event)).doesNotThrowAnyException();
        assertThat(WEBHOOK.takeRequest(5, TimeUnit.SECONDS)).isNotNull();   // it did attempt delivery
    }
}
