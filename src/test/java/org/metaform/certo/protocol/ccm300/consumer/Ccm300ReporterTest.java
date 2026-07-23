package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metaform.certo.MockSiglet;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.OutboundJsonClient;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.testsupport.InMemoryParticipantContextStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the consumer's acceptance callback emits a well-formed {@code CertificateAcceptanceStatus}
 * CloudEvent to the provider's {@code /certificate-acceptance-notifications} endpoint, using a
 * {@link MockWebServer} standing in for the provider. Uses {@code @SpringBootTest} only to obtain the
 * Jackson 3 {@link ObjectMapper} (no web server is started — default MOCK environment).
 */
@SpringBootTest
class Ccm300ReporterTest {

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OutboundJsonClient outbound;

    private MockWebServer provider;
    private Ccm300Reporter client;
    private OutboundCall call;

    @BeforeEach
    void setUp() throws Exception {
        provider = new MockWebServer();
        provider.start();
        var clock = Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneOffset.UTC);
        // The consumer tenant sends the acceptance report to the provider (its counterparty).
        var contexts = new InMemoryParticipantContextStore();
        var consumer = new ParticipantContext("pctx-consumer", "BPNL0000000002CD",
                "urn:bpn:BPNL0000000002CD", "did:web:consumer");
        contexts.save(consumer);
        contexts.save(new ParticipantContext("pctx-provider", "BPNL0000000001AB",
                "urn:bpn:BPNL0000000001AB", "did:web:provider"));
        // The mock siglet returns the (mock) provider's URL as the outbound endpoint for this flow.
        var siglet = new MockSiglet(contexts, provider.url("/").toString());
        var outboundTokens = new OutboundTokens(siglet::resolve);
        client = new Ccm300Reporter(outbound, outboundTokens, clock);
        // Sender = the consumer tenant; counterparty = the provider (BPN + DID supplied on the call).
        call = new OutboundCall(consumer, "BPNL0000000001AB", "did:web:provider", "flow-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        provider.shutdown();
    }

    @Test
    void reportsAcceptedAsCloudEvent() throws Exception {
        provider.enqueue(new MockResponse().setResponseCode(204));

        client.report(null, "exch-1", "cert-1", AcceptanceStatus.ACCEPTED, null, call);

        RecordedRequest request = provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/certificate-acceptance-notifications");
        assertThat(request.getHeader("Content-Type")).startsWith("application/cloudevents+json");

        var body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("specversion").asString()).isEqualTo("1.0");
        assertThat(body.get("type").asString()).isEqualTo("org.catena-x.ccm.CertificateAcceptanceStatus.v1");
        assertThat(body.get("source").asString()).isEqualTo("urn:bpn:BPNL0000000002CD");
        assertThat(body.get("sourcebpn").asString()).isEqualTo("BPNL0000000002CD");
        assertThat(body.get("subject").asString()).isEqualTo("BPNL0000000001AB");

        var data = body.get("data");
        assertThat(data.get("exchangeId").asString()).isEqualTo("exch-1");
        assertThat(data.get("certificateId").asString()).isEqualTo("cert-1");
        assertThat(data.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(data.has("errors")).isFalse(); // omitted when null
    }

    @Test
    void reportsRejectedWithErrors() throws Exception {
        provider.enqueue(new MockResponse().setResponseCode(204));

        client.report(null, "exch-2", "cert-2", AcceptanceStatus.REJECTED,
                List.of(new StatusError("Certificate has expired")), call);

        RecordedRequest request = provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        var data = mapper.readTree(request.getBody().readUtf8()).get("data");
        assertThat(data.get("status").asString()).isEqualTo("REJECTED");
        assertThat(data.get("errors").get(0).get("message").asString()).contains("expired");
    }

    @Test
    void deliveryFailureIsSwallowed() throws Exception {
        // Provider responds 404 (unknown exchange) — the callback is best-effort and must not throw.
        provider.enqueue(new MockResponse().setResponseCode(404));

        client.report(null, "exch-unknown", "cert-3", AcceptanceStatus.ACCEPTED, null, call);

        assertThat(provider.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }
}
