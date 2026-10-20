package org.metaform.certo.consumer;

import org.metaform.certo.testsupport.MockSiglet;
import org.metaform.certo.testsupport.ManagementTestAuth;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.metaform.certo.testsupport.TestTenants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.provider.domain.Certificate;
import org.metaform.certo.provider.domain.CertificateRevision;
import org.metaform.certo.provider.domain.Document;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
                "server.port=18080"
        })
@Import(MockSigletConfig.class)
class ConsumerCertificateApiTest {

    private static final String BASE = "http://localhost:18080";
    private static final String CE = "application/cloudevents+json";
    // The notify target: this same runtime acting as both provider (notifier) and consumer.
    private static final String SELF_TARGET = "\"consumerId\":\"" + TestTenants.CONSUMER_DID + "\"";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockSiglet siglet;

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

    @BeforeEach
    void setUp() {
        // Outbound loopback calls resolve back to this same app.
        siglet.setEndpoint(BASE);
    }

    @Test
    void createdLifecycleEvent_recordsKnownCertificate_thenRequestDelivers() throws Exception {
        // A lifecycle event is purely informational: it updates the known-certificate view, opens no exchange.
        var post = postNotification(lifecycleCreated("evt-created-1", TestTenants.ISO14001_CERT_ID, 1));
        assertThat(post.statusCode()).isEqualTo(204);

        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/" + TestTenants.ISO14001_CERT_ID + "").body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("revision").asInt()).isEqualTo(1);

        // Notification-initiated flow: the notified consumer opens an exchange by submitting a certificate
        // request; the provider already holds the certificate, so it is FULFILLED immediately.
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso14001", "SITE00000003AYRE"));
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");

        driveAccept(exchangeId);

        var status = getAcceptanceStatus(exchangeId);
        assertThat(status.statusCode()).isEqualTo(200);
        var body = mapper.readTree(status.body());
        assertThat(body.get("certificateId").asString()).isEqualTo(TestTenants.ISO14001_CERT_ID);
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void lifecycleModified_updatesConsumerKnownCertificate() throws Exception {
        assertThat(postNotification(lifecycleModified("cert-mod-x", 2)).statusCode()).isEqualTo(204);

        var view = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-mod-x").body());
        assertThat(view.get("revision").asInt()).isEqualTo(2);
        assertThat(view.get("lifecycleStatus").asString()).isEqualTo("MODIFIED");
    }

    @Test
    void lifecycleWithdrawn_marksConsumerKnownCertificateUnavailable() throws Exception {
        assertThat(postNotification(lifecycleWithdrawn("cert-wd-x")).statusCode()).isEqualTo(204);

        var view = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-wd-x").body());
        assertThat(view.get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void notifiedExpiredCertificate_requestedAndRejected() throws Exception {
        // cert-expired-0001 r1 is expired (until 2020-01-01). The notification is informational; the consumer
        // opens an exchange by request, retrieves, and the caller decides REJECTED because it is expired.
        assertThat(postNotification(lifecycleCreated("evt-created-2", TestTenants.EXPIRED_CERT_ID, 1)).statusCode())
                .isEqualTo(204);

        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iatf16949", "SITE00000003AYRE"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        assertThat(retrieve(exchangeId).statusCode()).isEqualTo(200);
        assertThat(accept(exchangeId,
                "{\"status\":\"REJECTED\",\"errors\":[{\"message\":\"Certificate has expired\"}],\"flowId\":\"flow-1\"}")
                .statusCode()).isEqualTo(202);

        var body = mapper.readTree(getAcceptanceStatus(exchangeId).body());
        assertThat(body.get("status").asString()).isEqualTo("REJECTED");
        assertThat(body.get("errors").get(0).get("message").asString()).contains("expired");
    }

    @Test
    void providerPollAcceptance_pullsConsumerVerdict() throws Exception {
        // A consumer-initiated request for a held certificate is FULFILLED immediately.
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso9001", "SITE00000003AYRE"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();
        var pollPath = "/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                + "/certificate-exchanges/" + exchangeId + "/poll-acceptance?flowId=flow-1";

        // Before any decision the consumer returns 404, so the poll leaves the exchange pre-acceptance.
        var before = postEmpty(pollPath);
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(before.body()).hasNonNull("acceptanceStatus")).isFalse();

        // The consumer decides; the provider then pulls that verdict via poll-acceptance.
        driveAccept(exchangeId);
        var after = postEmpty(pollPath);
        assertThat(mapper.readTree(after.body()).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_pushOnFulfillment_retrievesAndAccepts() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso14001", "SITE-PULL-1"));
        assertThat(initiate.statusCode()).isEqualTo(202);
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("CERTIFICATE_REQUESTED");

        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);

        // The backend issues the certificate (state only); the client then fulfills the waiting exchange,
        // which pushes FULFILLED to the consumer. The push only records now, so the caller drives the accept.
        addCertificate("iso14001", "SITE-PULL-1");
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/" + exchangeId + "/fulfill?flowId=flow-1");

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        driveAccept(exchangeId);
        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-exchanges/" + exchangeId).body())
                .get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_backendCannotIssue_recordsDeclinedNoAcceptance() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso14001", "SITE-FAIL-1"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        // The backend cannot issue the certificate: the provider declines the exchange and pushes DECLINED.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/" + exchangeId + "/decline?flowId=flow-1");

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("DECLINED");
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    /** A consumer-initiated request body naming the consumer tenant and the target provider. */
    private static String initiateBody(String type, String location) {
        return "{\"protocol\":\"sce-1.0\",\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"providerId\":\"" + TestTenants.PROVIDER_DID
                + "\",\"certificateType\":\"" + type + "\",\"certifiedLocations\":[{\"siteId\":\"" + location + "\"}],\"flowId\":\"flow-1\"}";
    }

    private void addCertificate(String type, String location) throws Exception {
        var docResp = postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents",
                "{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}");
        var documentId = mapper.readTree(docResp.body()).get("documentId").asString();
        var body = """
                {"certificateType":"%s","certificateTypeVersion":"2016","registrationNumber":"DE-CERT-0001",
                 "validFrom":"2020-01-01","validUntil":"2035-01-01",
                 "certifiedLocations":[{"legalEntityId":"LE000000TESTLE","addressId":"ADDR000000TESTAD","siteId":"%s","locationRole":"MAIN_LOCATION"}],
                 "documentIds":["%s"]}""".formatted(type, location, documentId);
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates", body);
    }

    @Test
    void consumerInitiatedPull_heldCertificate_fulfilledImmediatelyAndAccepted() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso9001", "SITE00000003AYRE"));
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");

        // Fulfillment only records state now; the caller drives the accept explicitly.
        driveAccept(exchangeId);
        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-exchanges/" + exchangeId).body())
                .get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void consumerInitiatedPull_backendDecline_recordsDeclinedNoAcceptance() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("iso50001", "SITE-DECLINE-1"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        // The provider declines the request (a business decision); DECLINED is pushed to the consumer.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/" + exchangeId + "/decline?flowId=flow-1");

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("DECLINED");
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    @Test
    void lifecycle_endToEnd_modifyThenWithdraw_consumerReacts() throws Exception {
        var cert = new Certificate("cert-e2e-lc", TestTenants.PROVIDER_PCTX, "e2elc", "2015", "REG-e2e", null,
                List.of(new CertifiedLocation("LE00000001AXS", "SITE0000000E2ELC", "ADDR000000000099", LocationRole.MAIN_LOCATION)),
                null, null);
        providerDocuments.save(new Document("doc-e2e-lc-r1", TestTenants.PROVIDER_PCTX, LocalDate.of(2024, 1, 1), "en", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
        cert.addRevision(new CertificateRevision(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), List.of("doc-e2e-lc-r1")));
        providerCertificates.save(cert);

        // Notify the consumer: the event is derived from the certificate's current state (CREATED, r1).
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/notify",
                "{\"protocol\":\"sce-1.0\",\"messageId\":\"3f0e8e2e-0000-4000-8000-00000000e2e1\",\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var created = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(created.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(created.get("revision").asInt()).isEqualTo(1);

        // State change: a new revision (upload its document, add the revision) moves the certificate to
        // MODIFIED; the notify derives MODIFIED r2 from that state.
        var docId = mapper.readTree(postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents",
                "{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}").body()).get("documentId").asString();
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/revisions",
                "{\"validFrom\":\"2026-01-01\",\"validUntil\":\"2029-01-01\",\"documentIds\":[\"" + docId + "\"]}");
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/notify",
                "{\"protocol\":\"sce-1.0\",\"messageId\":\"3f0e8e2e-0000-4000-8000-00000000e2e2\",\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var modified = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(modified.get("lifecycleStatus").asString()).isEqualTo("MODIFIED");
        assertThat(modified.get("revision").asInt()).isEqualTo(2);

        // State change (withdraw), then a notify per counterparty — the WITHDRAWN event is derived.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/withdraw");
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/notify",
                "{\"protocol\":\"sce-1.0\",\"messageId\":\"3f0e8e2e-0000-4000-8000-00000000e2e3\",\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var withdrawn = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(withdrawn.get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void duplicateLifecycleEvent_isIgnored() throws Exception {
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "CREATED", 1)).statusCode())
                .isEqualTo(204);
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "MODIFIED", 2)).statusCode())
                .isEqualTo(204);

        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-cdup").body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("revision").asInt()).isEqualTo(1);
    }

    /** A lifecycle event with an explicit id (so two events can share a source+id for dedup tests). */
    private static String lifecycleEvent(String id, String certificateId, String status, int revision) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source": "did:web:provider",
                  "id": "%s",
                  "data": { "status": "%s", "certificate": {
                            "certificateId": "%s", "revision": %d, "certificateType": "iso9001",
                            "validFrom": "2024-06-01", "validUntil": "2028-05-31" } }
                }
                """.formatted(id, status, certificateId, revision);
    }

    @Test
    void fulfillmentNotification_accepted() throws Exception {
        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateFulfillmentStatus.v1",
                  "source": "did:web:provider",
                  "id": "22222222-2222-2222-2222-222222222222",
                  "time": "2025-05-04T07:30:00Z",
                  "data": { "exchangeId": "exch-f-1", "certificateId": "cert-ccc", "status": "FULFILLED" }
                }
                """;
        assertThat(postNotification(event).statusCode()).isEqualTo(204);
    }

    @Test
    void notificationBatch_isAtomic_oneBadEventAppliesNone() throws Exception {
        var batch = "[" + lifecycleCreated("evt-atomic-1", "cert-atomic-1", 1)
                + "," + lifecycleMissingCertificate() + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(400);

        // The valid event in the rejected batch was not applied either: no known-certificate view exists.
        assertThat(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-atomic-1").statusCode())
                .isEqualTo(404);
    }

    @Test
    void batchOfEvents_accepted() throws Exception {
        var batch = "[" + lifecycleCreated("evt-batch-1", "cert-batch-1", 1)
                + "," + lifecycleWithdrawn("cert-eee") + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(204);

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-batch-1").body())
                .get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-eee").body())
                .get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
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
                  "type": "org.dsaf.sce.SomethingElse.v1",
                  "source": "did:web:provider",
                  "id": "33333333-3333-3333-3333-333333333333",
                  "data": { "certificate": { "certificateId": "cert-x" }, "status": "CREATED" }
                }
                """;
        assertThat(postNotification(event).statusCode()).isEqualTo(400);
    }

    // --- HTTP helpers --------------------------------------------------------------------------

    /** A valid bearer for the always-on protocol security (minted by the in-process token backend). */
    private String bearer() {
        // A provider->consumer push is addressed to the consumer tenant (aud = consumer DID), from the provider
        // as the sender (sub). The receiving tenant (audience) owns the resulting consumer-side exchange.
        return "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_DATASPACE_ID);
    }

    /**
     * Drives the management-side retrieve+accept the caller performs once an exchange is FULFILLED.
     * Asserts the retrieve (200) and the accept (202) as it goes.
     */
    private void driveAccept(String exchangeId) throws Exception {
        assertThat(retrieve(exchangeId).statusCode()).isEqualTo(200);
        assertThat(accept(exchangeId, "{\"status\":\"ACCEPTED\",\"flowId\":\"flow-1\"}").statusCode())
                .isEqualTo(202);
    }

    private HttpResponse<String> retrieve(String exchangeId) throws Exception {
        return postEmpty("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/exchanges/" + exchangeId + "/retrieve?flowId=flow-1");
    }

    private HttpResponse<String> accept(String exchangeId, String body) throws Exception {
        return postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/exchanges/" + exchangeId + "/accept", body);
    }

    private HttpResponse<String> postNotification(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE + "/notifications"))
                .header("Content-Type", CE)
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAcceptanceStatus(String exchangeId) throws Exception {
        return get("/acceptance-status/" + exchangeId);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Authorization", bearer()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String lifecycleCreated(String eventId, String certificateId, int revision) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source": "did:web:provider",
                  "subject": "LE0000000002CD",
                  "id": "%s",
                  "time": "2025-05-04T07:00:00Z",
                  "data": {
                    "status": "CREATED",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": %d,
                      "certificateType": "iso14001",
                      "validFrom": "2024-06-01",
                      "validUntil": "2027-05-31"
                    }
                  }
                }
                """.formatted(eventId, certificateId, revision);
    }


    /** A lifecycle event with a valid envelope but no certificate (structurally invalid). */
    private static String lifecycleMissingCertificate() {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source": "did:web:provider",
                  "id": "evt-bad-no-cert",
                  "data": {
                    "status": "CREATED"
                  }
                }
                """;
    }

    private static String lifecycleModified(String certificateId, int revision) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source": "did:web:provider",
                  "id": "evt-mod-%s",
                  "data": {
                    "status": "MODIFIED",
                    "certificate": {
                      "certificateId": "%s",
                      "revision": %d,
                      "certificateType": "iso9001",
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
                  "type": "org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source": "did:web:provider",
                  "id": "evt-wd-%s",
                  "data": {
                    "status": "WITHDRAWN",
                    "certificate": { "certificateId": "%s" }
                  }
                }
                """.formatted(certificateId, certificateId);
    }
}
