package org.metaform.certo.consumer;

import org.metaform.certo.testsupport.MockSiglet;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.metaform.certo.testsupport.TestTenants;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code RetryingHttpClient} is actually wired into the outbound adapter path (not just unit-tested in
 * isolation): a real acceptance report to a provider that returns {@code 503} then {@code 204} transparently
 * retries and succeeds, so the exchange is marked reported (delivery confirmed) — two requests reach the
 * provider for a single {@code accept}. Backoff is squeezed so the retry is fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18088",
        "certo.http.max-retries=2",
        "certo.http.min-backoff-millis=10",
        "certo.http.max-backoff-millis=50"
})
@Import(MockSigletConfig.class)
class OutboundRetryTest {

    private static final String BASE = "http://localhost:18088";
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockSiglet siglet;

    private MockWebServer provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new MockWebServer();
        provider.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        provider.shutdown();
    }

    @Test
    void aTransient5xxOnTheAcceptanceReportIsRetriedThenSucceeds() throws Exception {
        // Record the exchange locally via a loopback embedded publish.
        siglet.setEndpoint(BASE);
        var publish = postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                        + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish",
                "{\"embedded\":true,\"flowId\":\"flow-1\",\"consumerBpn\":\"" + TestTenants.CONSUMER_BPN
                        + "\",\"consumerDid\":\"" + TestTenants.CONSUMER_DID + "\"}");
        var exchangeId = mapper.readTree(publish.body()).get("exchangeId").asString();
        assertThat(retrieve(exchangeId).statusCode()).isEqualTo(200);

        // The provider is transiently unavailable, then recovers within one accept's report.
        provider.enqueue(new MockResponse().setResponseCode(503));
        provider.enqueue(new MockResponse().setResponseCode(204));
        siglet.setEndpoint(provider.url("/").toString());

        assertThat(accept(exchangeId, "ACCEPTED").statusCode()).isEqualTo(202);

        // The retry recovered delivery: two attempts (503 then 204), and the exchange is no longer awaiting a report.
        assertThat(provider.getRequestCount()).isEqualTo(2);
        assertThat(awaitingAcceptanceExchangeIds()).doesNotContain(exchangeId);
    }

    // --- helpers -------------------------------------------------------------------------------

    private java.util.List<String> awaitingAcceptanceExchangeIds() throws Exception {
        var page = postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/exchanges/query", "{\"awaitingAcceptanceOnly\":true}");
        var ids = new java.util.ArrayList<String>();
        mapper.readTree(page.body()).get("items").forEach(item -> ids.add(item.get("exchangeId").asString()));
        return ids;
    }

    private HttpResponse<String> retrieve(String exchangeId) throws Exception {
        return postEmpty("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/exchanges/" + exchangeId + "/retrieve?flowId=flow-1");
    }

    private HttpResponse<String> accept(String exchangeId, String status) throws Exception {
        return postJson("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX
                + "/consumer/exchanges/" + exchangeId + "/accept",
                "{\"status\":\"" + status + "\",\"flowId\":\"flow-1\"}");
    }

    private String bearer() {
        return "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_BPN);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json").header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path)).header("Authorization", bearer())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
