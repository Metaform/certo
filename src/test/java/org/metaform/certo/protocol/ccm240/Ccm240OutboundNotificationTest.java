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
 * Phase 4 outbound routing via approach #2: a provider-initiated legacy publish, where the caller
 * specifies the target consumer's legacy endpoint, is delivered as a legacy
 * {@code /companycertificate/available} message. The legacy consumer is stubbed with a {@link MockWebServer}.
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

    private MockWebServer legacyConsumer;

    @BeforeEach
    void setUp() throws Exception {
        legacyConsumer = new MockWebServer();
        legacyConsumer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        legacyConsumer.shutdown();
    }

    @Test
    void legacyPublish_toSpecifiedConsumer_sendsAvailableMessage() throws Exception {
        legacyConsumer.enqueue(new MockResponse().setResponseCode(200));

        var availableUrl = legacyConsumer.url("/companycertificate/available").toString();
        var trigger = """
                { "consumerBpn": "BPNL0000000002CD", "consumerUrl": "%s" }
                """.formatted(availableUrl);

        var response = post("/legacy/certificates/cert-iso9001-0001/publish", trigger);
        assertThat(response.statusCode()).isEqualTo(202);

        RecordedRequest available = legacyConsumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(available).isNotNull();
        assertThat(available.getPath()).isEqualTo("/companycertificate/available");
        var body = mapper.readTree(available.getBody().readUtf8());
        assertThat(body.get("header").get("context").asString())
                .isEqualTo("CompanyCertificateManagement-CCMAPI-Available:1.0.0");
        assertThat(body.get("content").get("documentId").asString()).isEqualTo("cert-iso9001-0001");
        assertThat(body.get("content").get("certificateType").asString()).isEqualTo("ISO9001");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
