package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.Document;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Certificate Consumer API against a real running server, so the consumer's OkHttp pull
 * of the provider's {@code GET /certificates/{id}} + {@code GET /documents/{id}} (same runtime) is
 * genuinely performed. A fixed clock makes the validity/expiry evaluation deterministic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "certo.provider-base-url=http://localhost:18080",
                "certo.consumer-base-url=http://localhost:18080"
        })
class ConsumerCertificateApiTest {

    private static final String BASE = "http://localhost:18080";
    private static final String CE = "application/cloudevents+json";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ProviderCertificateStore providerCertificates;

    @Autowired
    ProviderDocumentStore providerDocuments;

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
        // cert-iso14001-0001 r1 is valid (until 2027-05-31) at the fixed clock.
        var post = postNotification(lifecycleCreated("exch-created-1", "cert-iso14001-0001", 1));
        assertThat(post.statusCode()).isEqualTo(204);

        var status = getAcceptanceStatus("exch-created-1");
        assertThat(status.statusCode()).isEqualTo(200);
        var body = mapper.readTree(status.body());
        assertThat(body.get("exchangeId").asString()).isEqualTo("exch-created-1");
        assertThat(body.get("certificateId").asString()).isEqualTo("cert-iso14001-0001");
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");

        var known = mapper.readTree(get("/consumer/certificates/cert-iso14001-0001").body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("revision").asInt()).isEqualTo(1);
    }

    @Test
    void lifecycleModified_updatesConsumerKnownCertificate() throws Exception {
        assertThat(postNotification(lifecycleModified("cert-mod-x", 2)).statusCode()).isEqualTo(204);

        var view = mapper.readTree(get("/consumer/certificates/cert-mod-x").body());
        assertThat(view.get("revision").asInt()).isEqualTo(2);
        assertThat(view.get("lifecycleStatus").asString()).isEqualTo("MODIFIED");
    }

    @Test
    void lifecycleWithdrawn_marksConsumerKnownCertificateUnavailable() throws Exception {
        assertThat(postNotification(lifecycleWithdrawn("cert-wd-x")).statusCode()).isEqualTo(204);

        var view = mapper.readTree(get("/consumer/certificates/cert-wd-x").body());
        assertThat(view.get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void createdLifecycleEvent_retrievesExpiredCertificate_andRejects() throws Exception {
        // cert-expired-0001 r1 is expired (until 2020-01-01).
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
    void embeddedDocumentPush_acceptsFromInlineContent_withoutPull() throws Exception {
        // The certificate is NOT held by the provider, so a pull would 404 -> ERRORED. Accepting from the
        // inline content proves the embedded-document push path was used (no pull).
        var pdf = Base64.getEncoder().encodeToString("EMBEDDED-PDF".getBytes());
        assertThat(postNotification(lifecycleCreatedEmbedded("exch-emb-1", "cert-embedded-only", pdf)).statusCode())
                .isEqualTo(204);

        var body = mapper.readTree(getAcceptanceStatus("exch-emb-1").body());
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.get("certificateId").asString()).isEqualTo("cert-embedded-only");
    }

    @Test
    void providerEmbeddedPublish_consumerAcceptsFromInlineContent() throws Exception {
        var publish = http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/certificates/cert-iso14001-0001/publish?embedded=true"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(publish.statusCode()).isEqualTo(202);
        var exchangeId = mapper.readTree(publish.body()).get("exchangeId").asString();

        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_pushOnFulfillment_retrievesAndAccepts() throws Exception {
        var initiate = postJson("/consumer/certificate-requests",
                "{\"certificateType\":\"ISO14001\",\"certifiedLocations\":[\"BPNS-PULL-1\"]}");
        assertThat(initiate.statusCode()).isEqualTo(202);
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("ACKNOWLEDGED");

        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);

        assertThat(advanceProvider(exchangeId)).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(advanceProvider(exchangeId)).isEqualTo("FULFILLED");

        assertThat(mapper.readTree(get("/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(mapper.readTree(get("/certificate-exchanges/" + exchangeId).body())
                .get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_failedFulfillment_recordsFailedNoAcceptance() throws Exception {
        var initiate = postJson("/consumer/certificate-requests",
                "{\"certificateType\":\"ISO14001\",\"certifiedLocations\":[\"BPNFAIL\"]}");
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        assertThat(advanceProvider(exchangeId)).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(advanceProvider(exchangeId)).isEqualTo("FAILED");

        assertThat(mapper.readTree(get("/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("FAILED");
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    private String advanceProvider(String exchangeId) throws Exception {
        var response = postEmpty("/certificate-requests/" + exchangeId + "/advance");
        return mapper.readTree(response.body()).get("status").asString();
    }

    @Test
    void consumerInitiatedPull_heldCertificate_fulfilledImmediatelyAndAccepted() throws Exception {
        var initiate = postJson("/consumer/certificate-requests",
                "{\"certificateType\":\"ISO9001\",\"certifiedLocations\":[\"BPNS00000003AYRE\"]}");
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");

        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(mapper.readTree(get("/certificate-exchanges/" + exchangeId).body())
                .get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_unofferedType_declined() throws Exception {
        var initiate = postJson("/consumer/certificate-requests", "{\"certificateType\":\"NOT-OFFERED\"}");
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("DECLINED");
        assertThat(opened.get("errors").get(0).get("message").asString()).isNotEmpty();

        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    @Test
    void lifecycle_endToEnd_modifyThenWithdraw_consumerReacts() throws Exception {
        var cert = new Certificate("cert-e2e-lc", "E2ELC", "2015", "REG-e2e", "high", null,
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000099", "BPNS0000000E2ELC", LocationRole.MAIN_LOCATION)),
                null, null);
        providerDocuments.save(new Document("doc-e2e-lc-r1", LocalDate.of(2024, 1, 1), "en", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
        cert.addRevision(new CertificateRevision(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), List.of("doc-e2e-lc-r1")));
        providerCertificates.save(cert);

        postEmpty("/certificates/cert-e2e-lc/publish");   // -> consumer learns CREATED
        var created = mapper.readTree(get("/consumer/certificates/cert-e2e-lc").body());
        assertThat(created.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(created.get("revision").asInt()).isEqualTo(1);

        postEmpty("/certificates/cert-e2e-lc/modify");    // -> consumer learns MODIFIED r2
        var modified = mapper.readTree(get("/consumer/certificates/cert-e2e-lc").body());
        assertThat(modified.get("lifecycleStatus").asString()).isEqualTo("MODIFIED");
        assertThat(modified.get("revision").asInt()).isEqualTo(2);

        postEmpty("/certificates/cert-e2e-lc/withdraw");  // -> consumer learns WITHDRAWN
        var withdrawn = mapper.readTree(get("/consumer/certificates/cert-e2e-lc").body());
        assertThat(withdrawn.get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void duplicateLifecycleEvent_isIgnored() throws Exception {
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "CREATED", 1, "exch-cdup")).statusCode())
                .isEqualTo(204);
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "MODIFIED", 2, null)).statusCode())
                .isEqualTo(204);

        var known = mapper.readTree(get("/consumer/certificates/cert-cdup").body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("revision").asInt()).isEqualTo(1);
    }

    /** A lifecycle event with an explicit id (so two events can share a source+id for dedup tests). */
    private static String lifecycleEvent(String id, String certificateId, String status, int revision, String exchangeId) {
        var exch = exchangeId == null ? "" : "\"exchangeId\":\"" + exchangeId + "\",";
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "%s",
                  "data": { "status": "%s", %s"certificate": {
                            "certificateId": "%s", "revision": %d, "certificateType": "ISO9001",
                            "validFrom": "2024-06-01", "validUntil": "2028-05-31" } }
                }
                """.formatted(id, status, exch, certificateId, revision);
    }

    @Test
    void providerInitiatedPush_closesLoopBackToProvider() throws Exception {
        var publish = http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/certificates/cert-iso14001-0001/publish"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(publish.statusCode()).isEqualTo(202);

        var publication = mapper.readTree(publish.body());
        var exchangeId = publication.get("exchangeId").asString();
        assertThat(publication.get("consumerNotified").asBoolean()).isTrue();

        var consumerView = mapper.readTree(getAcceptanceStatus(exchangeId).body());
        assertThat(consumerView.get("status").asString()).isEqualTo("ACCEPTED");

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
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "22222222-2222-2222-2222-222222222222",
                  "time": "2025-05-04T07:30:00Z",
                  "data": { "exchangeId": "exch-f-1", "certificateId": "cert-ccc", "status": "FULFILLED" }
                }
                """;
        assertThat(postNotification(event).statusCode()).isEqualTo(204);
    }

    @Test
    void notificationBatch_isAtomic_oneBadEventAppliesNone() throws Exception {
        var batch = "[" + lifecycleCreated("exch-atomic-1", "cert-iso14001-0001", 1)
                + "," + lifecycleCreatedMissingExchangeId("cert-iso14001-0001") + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(400);

        assertThat(getAcceptanceStatus("exch-atomic-1").statusCode()).isEqualTo(404);
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
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "33333333-3333-3333-3333-333333333333",
                  "data": { "certificate": { "certificateId": "cert-x" }, "status": "CREATED" }
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
        return get("/certificate-acceptance-status/" + exchangeId);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String lifecycleCreated(String exchangeId, String certificateId, int revision) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "subject": "BPNL0000000002CD",
                  "id": "evt-%s",
                  "time": "2025-05-04T07:00:00Z",
                  "data": {
                    "status": "CREATED",
                    "exchangeId": "%s",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": %d,
                      "certificateType": "ISO14001",
                      "validFrom": "2024-06-01",
                      "validUntil": "2027-05-31"
                    }
                  }
                }
                """.formatted(exchangeId, exchangeId, certificateId, revision);
    }

    /** A CREATED event carrying the full certificate with its document content inline (embedded-document push). */
    private static String lifecycleCreatedEmbedded(String exchangeId, String certificateId, String contentBase64) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "subject": "BPNL0000000002CD",
                  "id": "evt-emb-%s",
                  "time": "2025-05-04T07:00:00Z",
                  "data": {
                    "status": "CREATED",
                    "exchangeId": "%s",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": 1,
                      "certificateType": "ISO9001",
                      "validFrom": "2024-06-01",
                      "validUntil": "2027-05-31",
                      "documents": [
                        { "documentId": "doc-emb-1", "mediaType": "application/pdf", "contentBase64": "%s" }
                      ]
                    }
                  }
                }
                """.formatted(exchangeId, exchangeId, certificateId, contentBase64);
    }

    /** A CREATED event with a valid envelope but missing the required data.exchangeId (structurally invalid). */
    private static String lifecycleCreatedMissingExchangeId(String certificateId) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "evt-bad-%s",
                  "data": {
                    "status": "CREATED",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": 1,
                      "certificateType": "ISO14001",
                      "validFrom": "2024-06-01",
                      "validUntil": "2027-05-31"
                    }
                  }
                }
                """.formatted(certificateId, certificateId);
    }

    private static String lifecycleModified(String certificateId, int revision) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "evt-mod-%s",
                  "data": {
                    "status": "MODIFIED",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": %d,
                      "certificateType": "ISO9001",
                      "validFrom": "2024-06-01",
                      "validUntil": "2028-05-31"
                    }
                  }
                }
                """.formatted(certificateId, certificateId, revision);
    }

    private static String lifecycleWithdrawn(String certificateId) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "sourcebpn": "BPNL0000000001AB",
                  "id": "evt-wd-%s",
                  "data": {
                    "status": "WITHDRAWN",
                    "certificate": { "certificateId": "%s" }
                  }
                }
                """.formatted(certificateId, certificateId);
    }
}
