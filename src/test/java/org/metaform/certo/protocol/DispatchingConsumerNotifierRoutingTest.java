package org.metaform.certo.protocol;

import org.metaform.certo.testsupport.MockSiglet;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.metaform.certo.testsupport.TestTenants;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider&rarr;consumer notifier is multiplexed: the {@code @Primary} {@link ConsumerNotifier} is
 * {@link DispatchingConsumerNotifier}, which reads each exchange's {@link ExchangeBinding#version()} and routes
 * to that version's adapter. This proves the routing end-to-end: two exchanges whose bindings differ only in
 * protocol version, notified through the <em>same</em> {@code ConsumerNotifier}, reach different wire endpoints —
 * v3 the CloudEvents {@code /certificate-notifications}, v2.4.0 the message-envelope {@code /companycertificate/available}.
 * A missing binding falls back to {@link ProtocolVersion#NATIVE} (v3), covered by the no-binding case.
 */
@SpringBootTest
@Import(MockSigletConfig.class)
class DispatchingConsumerNotifierRoutingTest {

    @Autowired
    ConsumerNotifier notifier;   // the @Primary DispatchingConsumerNotifier

    @Autowired
    ExchangeBindingStore bindings;

    @Autowired
    ParticipantContextStore contexts;

    @Autowired
    MockSiglet siglet;

    private MockWebServer consumer;
    private OutboundCall call;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new MockWebServer();
        consumer.start();
        // Every outbound adapter resolves the counterparty endpoint from the siglet cache; point it at the mock.
        siglet.setEndpoint(consumer.url("/").toString());
        var sender = contexts.find(TestTenants.PROVIDER_PCTX).orElseThrow();
        call = new OutboundCall(sender, TestTenants.CONSUMER_BPN, TestTenants.CONSUMER_DID, "flow-route");
    }

    @AfterEach
    void tearDown() throws Exception {
        consumer.shutdown();
        bindings.deleteById("exch-route-v3");
        bindings.deleteById("exch-route-v240");
    }

    @Test
    void v3BoundExchange_routesToTheCloudEventsEndpoint() throws Exception {
        bindings.record(new ExchangeBinding("exch-route-v3", "cert-route-v3", ProtocolVersion.CCM_3_0_0,
                CounterpartyRole.CONSUMER, TestTenants.CONSUMER_BPN, TestTenants.CONSUMER_DID, null));
        consumer.enqueue(new MockResponse().setResponseCode(204));

        var delivered = notifier.notifyFulfillment(
                new FulfillmentStatusData("exch-route-v3", "cert-route-v3", FulfillmentStatus.FULFILLED, null), call);

        assertThat(delivered).isTrue();
        RecordedRequest request = consumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/certificate-notifications");
        assertThat(request.getHeader("Content-Type")).startsWith("application/cloudevents+json");
    }

    @Test
    void v240BoundExchange_routesToTheMessageEnvelopeEndpoint() throws Exception {
        bindings.record(new ExchangeBinding("exch-route-v240", "cert-route-v240", ProtocolVersion.CCM_2_4_0,
                CounterpartyRole.CONSUMER, TestTenants.CONSUMER_BPN, TestTenants.CONSUMER_DID, "msg-1"));
        consumer.enqueue(new MockResponse().setResponseCode(200));

        var delivered = notifier.notifyFulfillment(
                new FulfillmentStatusData("exch-route-v240", "cert-route-v240", FulfillmentStatus.FULFILLED, null), call);

        assertThat(delivered).isTrue();
        RecordedRequest request = consumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/companycertificate/available");
        // Not a CloudEvent — the v2.4.0 adapter renders the message-envelope JSON.
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    }

    @Test
    void noBinding_fallsBackToNativeV3() throws Exception {
        // No binding recorded for this exchange → NATIVE (v3) → the CloudEvents endpoint.
        consumer.enqueue(new MockResponse().setResponseCode(204));

        notifier.notifyFulfillment(
                new FulfillmentStatusData("exch-route-unbound", "cert-x", FulfillmentStatus.FULFILLED, null), call);

        RecordedRequest request = consumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/certificate-notifications");
    }
}
