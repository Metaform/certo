package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.testsupport.MockSiglet;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.metaform.certo.testsupport.TestTenants;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the v2.4.0 consumer-facing adapter ({@code /companycertificate/push}) against a real
 * running server: an inbound 3.1.0 push is up-converted, ingested, and published, driving the v3
 * consumer to pull, evaluate and accept the certificate — then the adapter reports the acceptance back
 * to the provider's feedback URL as a {@code /companycertificate/status} message (Phases 3+4).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082"
        })
@Import(MockSigletConfig.class)
class Ccm240ConsumerControllerTest {

    private static final String BASE = "http://localhost:18082";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockSiglet siglet;

    private MockWebServer v240Provider;

    @BeforeEach
    void setUp() throws Exception {
        v240Provider = new MockWebServer();
        v240Provider.start();
        // The consumer reports acceptance (/companycertificate/status) back to the v2.4.0 provider; the mock
        // siglet returns that provider's URL as the outbound endpoint.
        siglet.setEndpoint(v240Provider.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        v240Provider.shutdown();
    }

    @Test
    void v240Push_ingestsUpConvertsAcceptsAndReportsStatusBack() throws Exception {
        v240Provider.enqueue(new MockResponse().setResponseCode(200)); // the /status callback

        var feedbackUrl = v240Provider.url("/").toString();   // base URL; the reporter appends /companycertificate/status
        var pdf = Base64.getEncoder().encodeToString("PDF-CONTENT".getBytes(StandardCharsets.UTF_8));
        var push = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Push:1.0.0",
                              "messageId": "66666666-6666-6666-6666-666666666666", "senderBpn": "BPNL0000000001AB",
                              "receiverBpn": "BPNL0000000002CD", "sentDateTime": "2025-05-04T07:00:00Z",
                              "version": "3.1.0", "senderFeedbackUrl": "%s" },
                  "content": {
                    "businessPartnerNumber": "BPNL000000000AAA",
                    "type": { "certificateType": "iso9001", "certificateVersion": "2015" },
                    "registrationNumber": "REG-PUSH-1",
                    "areaOfApplication": "Production",
                    "validFrom": "2023-01-25", "validUntil": "2030-01-01",
                    "issuer": { "issuerName": "TUV", "issuerBpn": "BPNL133631123120" },
                    "trustLevel": "high",
                    "document": { "creationDate": "2024-08-23T13:19:00.280+02:00", "documentID": "pushdoc-1",
                                  "contentType": "application/pdf", "contentBase64": "%s" } } }
                """.formatted(feedbackUrl, pdf);

        var pushResponse = post("/companycertificate/push", push);
        assertThat(pushResponse.statusCode()).isEqualTo(200);
        var ack = mapper.readTree(pushResponse.body());
        var certificateId = ack.get("certificateId").asString();
        var exchangeId = ack.get("exchangeId").asString();

        // The consumer learned the certificate (lifecycle CREATED) by pulling the ingested metadata...
        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/certificates/" + certificateId).body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");

        // Under always-on security a provider-initiated push does not auto-accept; a management client drives
        // the acceptance decision (and the report back to the provider) over its own flowId.
        var acceptResponse = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/exchanges/" + exchangeId + "/accept",
                "{\"status\":\"ACCEPTED\",\"flowId\":\"flow-1\"}");
        assertThat(acceptResponse.statusCode()).isEqualTo(202);

        // ...evaluated it as ACCEPTED (valid, retrievable document)...
        var acceptance = mapper.readTree(get("/certificate-acceptance-status/" + exchangeId).body());
        assertThat(acceptance.get("status").asString()).isEqualTo("ACCEPTED");

        // ...and reported that back to the v2.4.0 provider as a v2.4.0 /status message (down-mapped).
        RecordedRequest status = v240Provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(status).isNotNull();
        assertThat(status.getPath()).isEqualTo("/companycertificate/status");
        var body = mapper.readTree(status.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString()).isEqualTo("CompanyCertificateManagement-CCMAPI-Status:1.0.0");
        assertThat(body.get("content").get("documentId").asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); // a UUID asset id
        assertThat(body.get("content").get("certificateStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void v240Push_samecertRepushed_keepsIdentityAndIncrementsRevision() throws Exception {
        // Two pushes of the same certificate (same issuer + registrationNumber), delivered as separate messages.
        var first = mapper.readTree(post("/companycertificate/push",
                pushBody("11111111-1111-1111-1111-111111111111", "REG-CONT-1")).body());
        var second = mapper.readTree(post("/companycertificate/push",
                pushBody("22222222-2222-2222-2222-222222222222", "REG-CONT-1")).body());

        // Identity continuity: the derived certificateId is stable across re-pushes.
        var certificateId = first.get("certificateId").asString();
        assertThat(certificateId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(second.get("certificateId").asString()).isEqualTo(certificateId);

        // The re-push is recorded as revision 2 of the same known certificate, not a duplicate record.
        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificates/" + certificateId).body());
        assertThat(known.get("revision").asInt()).isEqualTo(2);
    }

    @Test
    void v240Push_duplicateMessageId_isIdempotent() throws Exception {
        // A retransmission repeats the messageId; the second delivery must not open a second exchange or bump
        // the revision (it is deduplicated), and returns the exchange the first delivery opened.
        var first = mapper.readTree(post("/companycertificate/push",
                pushBody("88888888-8888-8888-8888-888888888888", "REG-DUP-1")).body());
        var second = mapper.readTree(post("/companycertificate/push",
                pushBody("88888888-8888-8888-8888-888888888888", "REG-DUP-1")).body());

        var certificateId = first.get("certificateId").asString();
        assertThat(second.get("certificateId").asString()).isEqualTo(certificateId);
        assertThat(second.get("exchangeId").asString()).isEqualTo(first.get("exchangeId").asString());

        // The duplicate did not accrue a revision — the known certificate is still at revision 1.
        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificates/" + certificateId).body());
        assertThat(known.get("revision").asInt()).isEqualTo(1);
    }

    @Test
    void v240Available_opensExchange_thenRetrievePullsCertificate() throws Exception {
        // The provider's data plane returns the full 3.1.0 certificate on the pull (v2.4.0 asset pull).
        var pdf = Base64.getEncoder().encodeToString("PDF-CONTENT".getBytes(StandardCharsets.UTF_8));
        v240Provider.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(certificateBody("REG-AVAIL-1", pdf)));

        // A v2.4.0 documentId is a UUID (== the certificateId).
        var documentId = UUID.randomUUID().toString();
        var available = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Available:1.0.0",
                              "messageId": "77777777-7777-7777-7777-777777777777", "senderBpn": "BPNL0000000001AB",
                              "receiverBpn": "BPNL0000000002CD", "sentDateTime": "2025-05-04T07:00:00Z",
                              "version": "3.1.0" },
                  "content": { "documentId": "%s", "certificateType": "iso9001",
                               "locationBpns": ["BPNS000000000001"] } }
                """.formatted(documentId);

        var availableResponse = post("/companycertificate/available", available);
        assertThat(availableResponse.statusCode()).isEqualTo(200);
        var ack = mapper.readTree(availableResponse.body());
        assertThat(ack.get("certificateId").asString()).isEqualTo(documentId);
        var exchangeId = ack.get("exchangeId").asString();
        assertThat(exchangeId).isNotBlank();

        // The consumer learned the certificate as CREATED (by reference, before any pull)...
        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificates/" + documentId).body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("certificateType").asString()).isEqualTo("iso9001");

        // ...and a client-driven retrieve pulls the full 3.1.0 certificate over the flow.
        var retrieve = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/exchanges/" + exchangeId + "/retrieve?flowId=flow-1", "");
        assertThat(retrieve.statusCode()).isEqualTo(200);
        var retrieved = mapper.readTree(retrieve.body());
        assertThat(retrieved.get("certificate").get("certificateType").asString()).isEqualTo("iso9001");
        assertThat(retrieved.get("certificate").get("registrationNumber").asString()).isEqualTo("REG-AVAIL-1");
        assertThat(retrieved.get("documents").get(0).get("contentBase64").asString()).isEqualTo(pdf);

        // The pull was a GET to the provider's data-plane endpoint resolved from the flow.
        RecordedRequest pull = v240Provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(pull).isNotNull();
        assertThat(pull.getMethod()).isEqualTo("GET");
    }

    @Test
    void v240Available_duplicateMessageId_isIdempotent() throws Exception {
        var documentId = UUID.randomUUID().toString();
        var body = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Available:1.0.0",
                              "messageId": "99999999-9999-9999-9999-999999999999", "senderBpn": "BPNL0000000001AB",
                              "receiverBpn": "BPNL0000000002CD", "sentDateTime": "2025-05-04T07:00:00Z",
                              "version": "3.1.0" },
                  "content": { "documentId": "%s", "certificateType": "iso9001" } }
                """.formatted(documentId);

        var first = mapper.readTree(post("/companycertificate/available", body).body());
        var second = mapper.readTree(post("/companycertificate/available", body).body());

        // A re-notice returns the exchange the first opened rather than opening a second.
        assertThat(second.get("certificateId").asString()).isEqualTo(documentId);
        assertThat(second.get("exchangeId").asString()).isEqualTo(first.get("exchangeId").asString());
    }

    @Test
    void proactivePull_retrievesAndRecordsPartnerCertificate() throws Exception {
        // A proactive discovery pull with no prior notification (TC-CCM-01 shape): the client sets up the flow,
        // Certo reads the certificate over it and records it in the known-certificate view.
        var pdf = Base64.getEncoder().encodeToString("PDF-CONTENT".getBytes(StandardCharsets.UTF_8));
        v240Provider.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(certificateBody("REG-PULL-1", pdf)));

        var pullBody = """
                { "partnerBpn": "%s", "partnerDid": "%s", "flowId": "flow-1", "protocolVersion": "2.4.0" }
                """.formatted(TestTenants.PROVIDER_BPN, TestTenants.PROVIDER_DID);
        var response = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificates/pull", pullBody);
        assertThat(response.statusCode()).isEqualTo(200);
        var view = mapper.readTree(response.body());
        assertThat(view.get("certificate").get("certificateType").asString()).isEqualTo("iso9001");
        assertThat(view.get("documents").get(0).get("contentBase64").asString()).isEqualTo(pdf);

        // The pulled certificate is now in the tenant's known-certificate view, under the derived id.
        var derivedId = UUID.nameUUIDFromBytes("BPNL133631123120|REG-PULL-1".getBytes(StandardCharsets.UTF_8)).toString();
        var known = mapper.readTree(get("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificates/" + derivedId).body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");
        assertThat(known.get("certificateType").asString()).isEqualTo("iso9001");
    }

    @Test
    void v240Request_overManagement_sendsRequestAndMapsCompletedReply() throws Exception {
        // Certo-as-consumer opens a request against a v2.4.0 provider; the provider replies COMPLETED with the
        // documentId, which maps to the exchange's certificateId (FULFILLED).
        var documentId = UUID.randomUUID().toString();
        v240Provider.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"requestStatus\":\"COMPLETED\",\"documentId\":\"" + documentId + "\"}"));

        var initiate = """
                { "providerBpn": "%s", "providerDid": "%s", "certificateType": "iso9001",
                  "flowId": "flow-1", "protocolVersion": "2.4.0" }
                """.formatted(TestTenants.PROVIDER_BPN, TestTenants.PROVIDER_DID);
        var response = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificate-requests", initiate);
        assertThat(response.statusCode()).isEqualTo(202);
        var view = mapper.readTree(response.body());
        assertThat(view.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");
        assertThat(view.get("certificateId").asString()).isEqualTo(documentId);

        // The outbound message was a v2.4.0 /companycertificate/request with the requested type + certifiedBpn.
        RecordedRequest sent = v240Provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getPath()).isEqualTo("/companycertificate/request");
        var body = mapper.readTree(sent.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString())
                .isEqualTo("CompanyCertificateManagement-CCMAPI-Request:1.0.0");
        assertThat(body.get("content").get("certificateType").asString()).isEqualTo("iso9001");
        assertThat(body.get("content").get("certifiedBpn").asString()).isEqualTo(TestTenants.PROVIDER_BPN);
    }

    @Test
    void v240Request_inProgressReply_mapsToCertificationRequested() throws Exception {
        v240Provider.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"requestStatus\":\"IN_PROGRESS\"}"));

        var initiate = """
                { "providerBpn": "%s", "providerDid": "%s", "certificateType": "iso9001",
                  "flowId": "flow-1", "protocolVersion": "2.4.0" }
                """.formatted(TestTenants.PROVIDER_BPN, TestTenants.PROVIDER_DID);
        var response = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/certificate-requests", initiate);
        assertThat(response.statusCode()).isEqualTo(202);
        var view = mapper.readTree(response.body());
        assertThat(view.get("fulfillmentStatus").asString()).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(view.has("certificateId")).isFalse();  // no documentId until COMPLETED
    }

    /** A 3.1.0 certificate body a v2.4.0 provider data plane returns on a pull. */
    private static String certificateBody(String registrationNumber, String pdfBase64) {
        return """
                { "businessPartnerNumber": "BPNL000000000AAA",
                  "type": { "certificateType": "iso9001", "certificateVersion": "2015" },
                  "registrationNumber": "%s",
                  "areaOfApplication": "Production",
                  "validFrom": "2023-01-25", "validUntil": "2030-01-01",
                  "issuer": { "issuerName": "TUV", "issuerBpn": "BPNL133631123120" },
                  "trustLevel": "high",
                  "document": { "creationDate": "2024-08-23T13:19:00.280+02:00", "documentID": "pulldoc-1",
                                "contentType": "application/pdf", "contentBase64": "%s" } }
                """.formatted(registrationNumber, pdfBase64);
    }

    @Test
    void v240Reject_splitsCertificateAndLocationErrors() throws Exception {
        v240Provider.enqueue(new MockResponse().setResponseCode(200)); // the /status callback

        // Push an embedded cert so there is an exchange to reject.
        var pushed = mapper.readTree(post("/companycertificate/push",
                pushBody("55555555-5555-5555-5555-555555555555", "REG-REJ-1")).body());
        var exchangeId = pushed.get("exchangeId").asString();

        // Reject with one certificate-level error (no specifier) and one location-level error (site BPN).
        var accept = """
                { "status": "REJECTED",
                  "errors": [ { "message": "Certificate invalid" },
                              { "message": "Site not covered", "specifier": "BPNS000000000001" } ],
                  "flowId": "flow-1" }
                """;
        var acceptResponse = post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/exchanges/" + exchangeId + "/accept", accept);
        assertThat(acceptResponse.statusCode()).isEqualTo(202);

        // The outbound /status splits the errors: certificate-level → certificateErrors, site-scoped → locationErrors.
        RecordedRequest status = v240Provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(status).isNotNull();
        assertThat(status.getPath()).isEqualTo("/companycertificate/status");
        var content = mapper.readTree(status.getBody().readUtf8()).get("content");
        assertThat(content.get("certificateStatus").asString()).isEqualTo("REJECTED");
        assertThat(content.get("certificateErrors").get(0).get("message").asString()).isEqualTo("Certificate invalid");
        var locationError = content.get("locationErrors").get(0);
        assertThat(locationError.get("bpn").asString()).isEqualTo("BPNS000000000001");
        assertThat(locationError.get("locationErrors").get(0).get("message").asString()).isEqualTo("Site not covered");
    }

    /** A minimal v2.4.0 push body for the given message id and (identity-bearing) registration number. */
    private static String pushBody(String messageId, String registrationNumber) {
        var pdf = Base64.getEncoder().encodeToString("PDF-CONTENT".getBytes(StandardCharsets.UTF_8));
        return """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Push:1.0.0",
                              "messageId": "%s", "senderBpn": "BPNL0000000001AB",
                              "receiverBpn": "BPNL0000000002CD", "sentDateTime": "2025-05-04T07:00:00Z",
                              "version": "3.1.0" },
                  "content": {
                    "businessPartnerNumber": "BPNL000000000AAA",
                    "type": { "certificateType": "iso9001", "certificateVersion": "2015" },
                    "registrationNumber": "%s",
                    "validFrom": "2023-01-25", "validUntil": "2030-01-01",
                    "issuer": { "issuerName": "TUV", "issuerBpn": "BPNL133631123120" },
                    "trustLevel": "high",
                    "document": { "creationDate": "2024-08-23T13:19:00.280+02:00", "documentID": "pushdoc-1",
                                  "contentType": "application/pdf", "contentBase64": "%s" } } }
                """.formatted(messageId, registrationNumber, pdf);
    }

    // Every token-protected call here is consumer-facing (the v2.4.0 push to this consumer, and the
    // acceptance-status query), so the token is addressed to the consumer tenant (aud = consumer DID) with the
    // provider as sender. The push thus stamps the consumer exchange/known-certificate with CONSUMER_PCTX.
    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_BPN))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Authorization", "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_BPN))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
