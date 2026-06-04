package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Certificate Consumer Notification API against a real running server, so the consumer's
 * OkHttp call out to the provider's {@code GET /certificates/{id}} (same runtime) is genuinely
 * performed. A fixed clock makes the validity/expiry evaluation deterministic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "certo.provider-base-url=http://localhost:18080",
                "certo.consumer-base-url=http://localhost:18080"
        })
class CertificateConsumerApiTest {

    private static final String BASE = "http://localhost:18080";
    private static final String CE = "application/cloudevents+json";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    /** Fixes "now" to 2026-06-04 so expiry checks are deterministic regardless of the real date. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void createdLifecycleEvent_retrievesValidCertificate_andAccepts() throws Exception {
        // cert-iso14001-0001 v1 is valid (until 2027-05-31) at the fixed clock.
        var post = postNotification(lifecycleCreated("exch-created-1", "cert-iso14001-0001", 1));
        assertThat(post.statusCode()).isEqualTo(204);

        var status = getAcceptanceStatus("exch-created-1");
        assertThat(status.statusCode()).isEqualTo(200);
        var body = mapper.readTree(status.body());
        assertThat(body.get("exchangeId").asString()).isEqualTo("exch-created-1");
        assertThat(body.get("certificateId").asString()).isEqualTo("cert-iso14001-0001");
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void createdLifecycleEvent_retrievesExpiredCertificate_andRejects() throws Exception {
        // cert-expired-0001 v1 is expired (until 2020-01-01).
        assertThat(postNotification(lifecycleCreated("exch-created-2", "cert-expired-0001", 1)).statusCode())
                .isEqualTo(204);

        var body = mapper.readTree(getAcceptanceStatus("exch-created-2").body());
        assertThat(body.get("status").asString()).isEqualTo("REJECTED");
        assertThat(body.get("errors").get(0).get("message").asString()).contains("expired");
    }

    @Test
    void createdLifecycleEvent_unknownCertificate_isErrored() throws Exception {
        // No such certificate at the provider -> retrieval 404 -> ERRORED.
        assertThat(postNotification(lifecycleCreated("exch-created-3", "cert-does-not-exist", 1)).statusCode())
                .isEqualTo(204);

        var body = mapper.readTree(getAcceptanceStatus("exch-created-3").body());
        assertThat(body.get("status").asString()).isEqualTo("ERRORED");
        assertThat(body.get("errors").get(0).get("message").asString()).isNotEmpty();
    }

    @Test
    void providerInitiatedPush_closesLoopBackToProvider() throws Exception {
        // Provider publishes a held certificate: opens an exchange, notifies the consumer, the consumer
        // retrieves + decides, then posts acceptance back to the provider — all synchronously.
        var publish = http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/certificates/cert-iso14001-0001/publish"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(publish.statusCode()).isEqualTo(202);

        var publication = mapper.readTree(publish.body());
        var exchangeId = publication.get("exchangeId").asString();
        assertThat(publication.get("consumerNotified").asBoolean()).isTrue();

        // The consumer recorded its decision locally.
        var consumerView = mapper.readTree(getAcceptanceStatus(exchangeId).body());
        assertThat(consumerView.get("status").asString()).isEqualTo("ACCEPTED");

        // And the provider recorded the acceptance the consumer posted back — the loop is closed.
        var providerView = mapper.readTree(http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/certificate-exchanges/" + exchangeId)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(providerView.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        assertThat(providerView.get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void fulfillmentNotification_accepted() throws Exception {
        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateFulfillmentStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "22222222-2222-2222-2222-222222222222",
                  "time": "2025-05-04T07:30:00Z",
                  "data": { "exchangeId": "exch-f-1", "certificateId": "cert-ccc", "status": "FULFILLED" }
                }
                """;
        assertThat(postNotification(event).statusCode()).isEqualTo(204);
    }

    @Test
    void batchOfEvents_accepted() throws Exception {
        var batch = "[" + lifecycleCreated("exch-batch-1", "cert-iso14001-0001", 1)
                + "," + lifecycleWithdrawn("cert-eee") + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(204);

        var body = mapper.readTree(getAcceptanceStatus("exch-batch-1").body());
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptanceStatus_unknownExchange_notFound() throws Exception {
        assertThat(getAcceptanceStatus("exch-nope").statusCode()).isEqualTo(404);
    }

    @Test
    void unsupportedEventType_badRequest() throws Exception {
        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.SomethingElse.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "33333333-3333-3333-3333-333333333333",
                  "data": { "certificateId": "cert-x", "status": "CREATED" }
                }
                """;
        assertThat(postNotification(event).statusCode()).isEqualTo(400);
    }

    // --- HTTP helpers --------------------------------------------------------------------------

    private HttpResponse<String> postNotification(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE + "/certificate-notifications"))
                .header("Content-Type", CE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAcceptanceStatus(String exchangeId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE + "/certificate-acceptance-status/" + exchangeId))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String lifecycleCreated(String exchangeId, String certificateId, int version) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "subject": "BPNL0000000002CD",
                  "id": "a1b2c3d4-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
                  "time": "2025-05-04T07:00:00Z",
                  "data": {
                    "exchangeId": "%s",
                    "certificateId": "%s",
                    "version": %d,
                    "status": "CREATED",
                    "datasetId": "dataset-ccm-cert-abc123",
                    "certificateType": "ISO14001",
                    "validFrom": "2024-06-01",
                    "validUntil": "2027-05-31"
                  }
                }
                """.formatted(exchangeId, certificateId, version);
    }

    private static String lifecycleWithdrawn(String certificateId) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "c3d4e5f6-7a8b-9c0d-1e2f-3a4b5c6d7e8f",
                  "data": {
                    "certificateId": "%s",
                    "version": 2,
                    "status": "WITHDRAWN",
                    "datasetId": "dataset-ccm-cert-abc123",
                    "certificateType": "ISO9001"
                  }
                }
                """.formatted(certificateId);
    }
}
