package org.metaform.certo.protocol.ccm240;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the legacy v2.4.0 consumer-facing adapter ({@code /companycertificate/push}) against a real
 * running server: an inbound 3.1.0 push is up-converted, ingested, and published, driving the v3
 * consumer to pull, evaluate and accept the certificate — then the adapter reports the acceptance back
 * to the (legacy) provider's feedback URL as a {@code /companycertificate/status} message (Phases 3+4).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "certo.provider-base-url=http://localhost:18082",
                "certo.consumer-base-url=http://localhost:18082"
        })
class Ccm240ConsumerControllerTest {

    private static final String BASE = "http://localhost:18082";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    private MockWebServer legacyProvider;

    @BeforeEach
    void setUp() throws Exception {
        legacyProvider = new MockWebServer();
        legacyProvider.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        legacyProvider.shutdown();
    }

    @Test
    void legacyPush_ingestsUpConvertsAcceptsAndReportsStatusBack() throws Exception {
        legacyProvider.enqueue(new MockResponse().setResponseCode(200)); // the /status callback

        var feedbackUrl = legacyProvider.url("/companycertificate/status").toString();
        var pdf = Base64.getEncoder().encodeToString("PDF-CONTENT".getBytes(StandardCharsets.UTF_8));
        var push = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Push:1.0.0",
                              "messageId": "leg-push-1", "senderBpn": "BPNL0000000001AB",
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
        var known = mapper.readTree(get("/consumer/certificates/" + certificateId).body());
        assertThat(known.get("lifecycleStatus").asString()).isEqualTo("CREATED");

        // ...evaluated it as ACCEPTED (valid, retrievable document)...
        var acceptance = mapper.readTree(get("/certificate-acceptance-status/" + exchangeId).body());
        assertThat(acceptance.get("status").asString()).isEqualTo("ACCEPTED");

        // ...and reported that back to the legacy provider as a v2.4.0 /status message (down-mapped).
        RecordedRequest status = legacyProvider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(status).isNotNull();
        assertThat(status.getPath()).isEqualTo("/companycertificate/status");
        var body = mapper.readTree(status.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString()).isEqualTo("CompanyCertificateManagement-CCMAPI-Status:1.0.0");
        assertThat(body.get("content").get("documentId").asString()).isEqualTo(certificateId);
        assertThat(body.get("content").get("certificateStatus").asString()).isEqualTo("ACCEPTED");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
