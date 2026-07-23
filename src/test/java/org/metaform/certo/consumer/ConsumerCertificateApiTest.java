package org.metaform.certo.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metaform.certo.MockSiglet;
import org.metaform.certo.MockSigletConfig;
import org.metaform.certo.TestTenants;
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
                "server.port=18080"
        })
@Import(MockSigletConfig.class)
class ConsumerCertificateApiTest {

    private static final String BASE = "http://localhost:18080";
    private static final String CE = "application/cloudevents+json";
    // The publish source tenant + target: this same runtime acting as both provider (publisher) and consumer.
    private static final String SELF_TARGET = "\"consumerBpn\":\"" + TestTenants.CONSUMER_BPN
            + "\",\"consumerDid\":\"" + TestTenants.CONSUMER_DID + "\"";

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
    void createdLifecycleEvent_retrievesValidCertificate_andAccepts() throws Exception {
        // cert-iso14001-0001 r1 is valid (until 2027-05-31) at the fixed clock.
        var post = postNotification(lifecycleCreated("exch-created-1", TestTenants.ISO14001_CERT_ID, 1));
        assertThat(post.statusCode()).isEqualTo(204);

        // The push only records the exchange now; the caller drives retrieve+accept via the management API.
        driveAccept("exch-created-1");

        var status = getAcceptanceStatus("exch-created-1");
        assertThat(status.statusCode()).isEqualTo(200);
        var body = mapper.readTree(status.body());
        assertThat(body.get("exchangeId").asString()).isEqualTo("exch-created-1");
        assertThat(body.get("certificateId").asString()).isEqualTo(TestTenants.ISO14001_CERT_ID);
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");

        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/" + TestTenants.ISO14001_CERT_ID + "").body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("revision").asInt()).isEqualTo(1);
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
    void createdLifecycleEvent_retrievesExpiredCertificate_andRejects() throws Exception {
        // cert-expired-0001 r1 is expired (until 2020-01-01).
        assertThat(postNotification(lifecycleCreated("exch-created-2", TestTenants.EXPIRED_CERT_ID, 1)).statusCode())
                .isEqualTo(204);

        // Retrieve succeeds (the provider holds it), but the caller decides REJECTED because it is expired.
        assertThat(retrieve("exch-created-2").statusCode()).isEqualTo(200);
        assertThat(accept("exch-created-2",
                "{\"status\":\"REJECTED\",\"errors\":[{\"message\":\"Certificate has expired\"}],\"flowId\":\"flow-1\"}")
                .statusCode()).isEqualTo(202);

        var body = mapper.readTree(getAcceptanceStatus("exch-created-2").body());
        assertThat(body.get("status").asString()).isEqualTo("REJECTED");
        assertThat(body.get("errors").get(0).get("message").asString()).contains("expired");
    }

    @Test
    void createdLifecycleEvent_unknownCertificate_retrievalFails() throws Exception {
        // No such certificate at the provider -> the caller's retrieve fails (provider 404 -> 502 bad gateway).
        assertThat(postNotification(lifecycleCreated("exch-created-3", "cert-does-not-exist", 1)).statusCode())
                .isEqualTo(204);

        // The push no longer auto-decides; driving retrieve surfaces the provider's absence as a failure.
        assertThat(retrieve("exch-created-3").statusCode()).isEqualTo(502);
    }

    @Test
    void embeddedDocumentPush_acceptsFromInlineContent_withoutPull() throws Exception {
        // The certificate is NOT held by the provider, so a pull would 404 -> ERRORED. Accepting from the
        // inline content proves the embedded-document push path was used (no pull).
        var pdf = Base64.getEncoder().encodeToString("EMBEDDED-PDF".getBytes());
        assertThat(postNotification(lifecycleCreatedEmbedded("exch-emb-1", "cert-embedded-only", pdf)).statusCode())
                .isEqualTo(204);

        // The caller drives the accept; retrieve returns the inline content kept at push time (no pull).
        driveAccept("exch-emb-1");

        var body = mapper.readTree(getAcceptanceStatus("exch-emb-1").body());
        assertThat(body.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.get("certificateId").asString()).isEqualTo("cert-embedded-only");
    }

    @Test
    void providerEmbeddedPublish_consumerAcceptsFromInlineContent() throws Exception {
        var publish = http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"embedded\":true,\"flowId\":\"flow-1\"," + SELF_TARGET + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(publish.statusCode()).isEqualTo(202);
        var exchangeId = mapper.readTree(publish.body()).get("exchangeId").asString();

        // The embedded push records the exchange with inline content; the caller drives the accept.
        driveAccept(exchangeId);

        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
    }

    @Test
    void providerPollAcceptance_pullsConsumerVerdict() throws Exception {
        // Provider publishes (embedded) to the consumer, opening an exchange the consumer will accept.
        var publish = postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish",
                "{\"embedded\":true,\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var exchangeId = mapper.readTree(publish.body()).get("exchangeId").asString();
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
    void providerPublish_sameIdempotencyKey_reusesExchange_differentKeyOpensNew() throws Exception {
        var publishPath = "/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish";
        var keyed = "{\"flowId\":\"flow-1\",\"idempotencyKey\":\"pub-key-1\"," + SELF_TARGET + "}";

        var first = mapper.readTree(postJson(publishPath, keyed).body());
        var second = mapper.readTree(postJson(publishPath, keyed).body());
        // Same idempotencyKey → the re-publish reused the still-live exchange rather than opening a duplicate.
        assertThat(second.get("exchangeId").asString()).isEqualTo(first.get("exchangeId").asString());

        // A different key opens a genuinely new exchange (multiple exchanges may concern the same certificate).
        var third = mapper.readTree(postJson(publishPath,
                "{\"flowId\":\"flow-1\",\"idempotencyKey\":\"pub-key-2\"," + SELF_TARGET + "}").body());
        assertThat(third.get("exchangeId").asString()).isNotEqualTo(first.get("exchangeId").asString());
    }

    @Test
    void consumerInitiatedPull_pushOnFulfillment_retrievesAndAccepts() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("ISO14001", "BPNS-PULL-1"));
        assertThat(initiate.statusCode()).isEqualTo(202);
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("CERTIFICATION_REQUESTED");

        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);

        // The backend issues the certificate (state only); the client then fulfills the waiting exchange,
        // which pushes FULFILLED to the consumer. The push only records now, so the caller drives the accept.
        addCertificate("ISO14001", "BPNS-PULL-1");
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
    void consumerInitiatedPull_backendFailure_recordsFailedNoAcceptance() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("ISO14001", "BPNS-FAIL-1"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        // The backend cannot issue the certificate: the provider fails the exchange and pushes FAILED.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/" + exchangeId + "/fail?flowId=flow-1");

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("FAILED");
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    /** A consumer-initiated request body naming the consumer tenant and the target provider. */
    private static String initiateBody(String type, String location) {
        return "{\"providerBpn\":\"" + TestTenants.PROVIDER_BPN + "\",\"providerDid\":\"" + TestTenants.PROVIDER_DID
                + "\",\"certificateType\":\"" + type + "\",\"certifiedLocations\":[\"" + location + "\"],\"flowId\":\"flow-1\"}";
    }

    private void addCertificate(String type, String location) throws Exception {
        var docResp = postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents",
                "{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}");
        var documentId = mapper.readTree(docResp.body()).get("documentId").asString();
        var body = """
                {"certificateType":"%s","certificateTypeVersion":"2016","registrationNumber":"DE-CERT-0001",
                 "validFrom":"2020-01-01","validUntil":"2035-01-01","trustLevel":"high",
                 "certifiedLocations":[{"bpnl":"BPNL000000TESTLE","bpna":"BPNA000000TESTAD","bpns":"%s","locationRole":"MAIN_LOCATION"}],
                 "documentIds":["%s"]}""".formatted(type, location, documentId);
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates", body);
    }

    @Test
    void consumerInitiatedPull_heldCertificate_fulfilledImmediatelyAndAccepted() throws Exception {
        var initiate = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests",
                initiateBody("ISO9001", "BPNS00000003AYRE"));
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
                initiateBody("ISO50001", "BPNS-DECLINE-1"));
        var exchangeId = mapper.readTree(initiate.body()).get("exchangeId").asString();

        // The provider declines the request (a business decision); DECLINED is pushed to the consumer.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/" + exchangeId + "/decline?flowId=flow-1");

        assertThat(mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificate-requests/" + exchangeId).body())
                .get("fulfillmentStatus").asString()).isEqualTo("DECLINED");
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404);
    }

    @Test
    void lifecycle_endToEnd_modifyThenWithdraw_consumerReacts() throws Exception {
        var cert = new Certificate("cert-e2e-lc", TestTenants.PROVIDER_PCTX, "E2ELC", "2015", "REG-e2e", "high", null,
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000099", "BPNS0000000E2ELC", LocationRole.MAIN_LOCATION)),
                null, null);
        providerDocuments.save(new Document("doc-e2e-lc-r1", TestTenants.PROVIDER_PCTX, LocalDate.of(2024, 1, 1), "en", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
        cert.addRevision(new CertificateRevision(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), List.of("doc-e2e-lc-r1")));
        providerCertificates.save(cert);

        // Notify the (native) consumer of the CREATED cert.
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/publish", "{\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var created = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(created.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(created.get("revision").asInt()).isEqualTo(1);

        // State change: create a new version (upload its document, then add the revision), then publish MODIFIED.
        var docId = mapper.readTree(postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents",
                "{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}").body()).get("documentId").asString();
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/revisions",
                "{\"validFrom\":\"2026-01-01\",\"validUntil\":\"2029-01-01\",\"documentIds\":[\"" + docId + "\"]}");
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/publish",
                "{\"lifecycleStatus\":\"MODIFIED\",\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var modified = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(modified.get("lifecycleStatus").asString()).isEqualTo("MODIFIED");
        assertThat(modified.get("revision").asInt()).isEqualTo(2);

        // State change (withdraw), then a separate targeted publish of the WITHDRAWN event.
        postEmpty("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/withdraw");
        postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/cert-e2e-lc/publish",
                "{\"lifecycleStatus\":\"WITHDRAWN\",\"flowId\":\"flow-1\"," + SELF_TARGET + "}");
        var withdrawn = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-e2e-lc").body());
        assertThat(withdrawn.get("lifecycleStatus").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void duplicateLifecycleEvent_isIgnored() throws Exception {
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "CREATED", 1, "exch-cdup")).statusCode())
                .isEqualTo(204);
        assertThat(postNotification(lifecycleEvent("dup-1", "cert-cdup", "MODIFIED", 2, null)).statusCode())
                .isEqualTo(204);

        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/cert-cdup").body());
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
                HttpRequest.newBuilder(URI.create(BASE + "/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"flowId\":\"flow-1\"," + SELF_TARGET + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(publish.statusCode()).isEqualTo(202);

        var publication = mapper.readTree(publish.body());
        var exchangeId = publication.get("exchangeId").asString();
        assertThat(publication.get("consumerNotified").asBoolean()).isTrue();

        // The push records the exchange; the caller drives retrieve+accept, which reports back to the provider.
        driveAccept(exchangeId);

        var consumerView = mapper.readTree(getAcceptanceStatus(exchangeId).body());
        assertThat(consumerView.get("status").asString()).isEqualTo("ACCEPTED");

        var providerView = mapper.readTree(http.send(
                HttpRequest.newBuilder(URI.create(BASE + "/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-exchanges/" + exchangeId)).GET().build(),
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
        var batch = "[" + lifecycleCreated("exch-atomic-1", TestTenants.ISO14001_CERT_ID, 1)
                + "," + lifecycleCreatedMissingExchangeId(TestTenants.ISO14001_CERT_ID) + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(400);

        assertThat(getAcceptanceStatus("exch-atomic-1").statusCode()).isEqualTo(404);
    }

    @Test
    void batchOfEvents_accepted() throws Exception {
        var batch = "[" + lifecycleCreated("exch-batch-1", TestTenants.ISO14001_CERT_ID, 1)
                + "," + lifecycleWithdrawn("cert-eee") + "]";
        assertThat(postNotification(batch).statusCode()).isEqualTo(204);

        // The CREATED event only records the exchange now; the caller drives the accept.
        driveAccept("exch-batch-1");

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

    /** A valid bearer for the always-on CCM protocol security (minted by the in-process token backend). */
    private String bearer() {
        // A provider->consumer push is addressed to the consumer tenant (aud = consumer DID), from the provider
        // as the sender (sub/bpn). The receiving tenant (audience) owns the resulting consumer-side exchange.
        return "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_BPN);
    }

    /**
     * Drives the management-side retrieve+accept a caller must now perform after a provider-initiated push
     * (the push only records state; it no longer auto-evaluates). Asserts the retrieve (200) and the accept
     * (202) as it goes.
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
        var request = HttpRequest.newBuilder(URI.create(BASE + "/certificate-notifications"))
                .header("Content-Type", CE)
                .header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAcceptanceStatus(String exchangeId) throws Exception {
        return get("/certificate-acceptance-status/" + exchangeId);
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
