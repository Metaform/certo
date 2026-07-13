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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 outbound routing: a provider-initiated publish over protocol version {@code 2.4.0} (the caller
 * names the target consumer's base URL) is delivered to that consumer — by reference as a
 * {@code /companycertificate/available} message, or embedded as a {@code /companycertificate/push} carrying
 * the full certificate inline. The target consumer is stubbed with a {@link MockWebServer}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18084",
                "certo.provider-base-url=http://localhost:18084",
                "certo.consumer-base-url=http://localhost:18084"
        })
class Ccm240OutboundNotificationTest {

    private static final String BASE = "http://localhost:18084";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    private MockWebServer targetConsumer;

    @BeforeEach
    void setUp() throws Exception {
        targetConsumer = new MockWebServer();
        targetConsumer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        targetConsumer.shutdown();
    }

    @Test
    void publish_v240ByReference_sendsAvailableMessage() throws Exception {
        targetConsumer.enqueue(new MockResponse().setResponseCode(200));
        var trigger = """
                {"protocolVersion":"2.4.0","consumerBpn":"BPNL0000000002CD","consumerUrl":"%s"}"""
                .formatted(targetConsumer.url("/").toString());

        var response = post("/management/v1/certificates/cert-iso9001-0001/publish", trigger);
        assertThat(response.statusCode()).isEqualTo(202);

        RecordedRequest available = targetConsumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(available).isNotNull();
        assertThat(available.getPath()).isEqualTo("/companycertificate/available");
        var body = mapper.readTree(available.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString())
                .isEqualTo("CompanyCertificateManagement-CCMAPI-Available:1.0.0");
        assertThat(body.get("content").get("documentId").asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); // a UUID asset id
        assertThat(body.get("content").get("certificateType").asString()).isEqualTo("ISO9001");
    }

    @Test
    void publish_v240Embedded_sendsPushWithFullCertificateInline() throws Exception {
        targetConsumer.enqueue(new MockResponse().setResponseCode(200));
        var trigger = """
                {"protocolVersion":"2.4.0","embedded":true,"consumerBpn":"BPNL0000000002CD","consumerUrl":"%s"}"""
                .formatted(targetConsumer.url("/").toString());

        var response = post("/management/v1/certificates/cert-iso9001-0001/publish", trigger);
        assertThat(response.statusCode()).isEqualTo(202);

        RecordedRequest push = targetConsumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(push).isNotNull();
        assertThat(push.getPath()).isEqualTo("/companycertificate/push");
        var body = mapper.readTree(push.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString())
                .isEqualTo("CompanyCertificateManagement-CCMAPI-Push:1.0.0");
        // The full BusinessPartnerCertificate 3.1.0 is inline, including the document content.
        assertThat(body.get("content").get("type").get("certificateType").asString()).isEqualTo("ISO9001");
        assertThat(body.get("content").get("document").get("contentBase64").asString()).isNotEmpty();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
