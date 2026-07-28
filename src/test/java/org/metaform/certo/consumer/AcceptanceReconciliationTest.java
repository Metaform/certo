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
 * T6 durable-outbound reconciliation (consumer &rarr; provider acceptance). The acceptance report is
 * best-effort and post-commit, so a lost report leaves the verdict recorded locally but undelivered. The fix:
 * the exchange is marked reported <em>only</em> on successful delivery, so the reconciliation query
 * ({@code POST /consumer/exchanges/query} with {@code awaitingAcceptanceOnly}) keeps surfacing a failed report
 * for a re-drive — and a re-drive over a working endpoint clears it. Retries are off so the failed delivery
 * fails fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18090",
        "certo.http.max-retries=0"
})
@Import(MockSigletConfig.class)
class AcceptanceReconciliationTest {

    private static final String BASE = "http://localhost:18090";
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
    void aLostAcceptanceReportStaysSurfacedUntilItIsRedelivered() throws Exception {
        // 1. Provider publishes (embedded) to the consumer over the loopback so the exchange is recorded locally.
        siglet.setEndpoint(BASE);
        var publish = postJson("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                        + "/certificates/" + TestTenants.ISO14001_CERT_ID + "/publish",
                "{\"embedded\":true,\"flowId\":\"flow-1\",\"consumerBpn\":\"" + TestTenants.CONSUMER_BPN
                        + "\",\"consumerDid\":\"" + TestTenants.CONSUMER_DID + "\"}");
        var exchangeId = mapper.readTree(publish.body()).get("exchangeId").asString();
        assertThat(retrieve(exchangeId).statusCode()).isEqualTo(200);   // inline content, no outbound pull

        // 2. Redirect the acceptance report to a controllable provider: first delivery fails (503), the re-drive succeeds (204).
        provider.enqueue(new MockResponse().setResponseCode(503));
        provider.enqueue(new MockResponse().setResponseCode(204));
        siglet.setEndpoint(provider.url("/").toString());

        // 3. First accept: the report cannot be delivered, so the exchange must NOT be marked reported.
        assertThat(accept(exchangeId, "ACCEPTED").statusCode()).isEqualTo(202);
        assertThat(provider.getRequestCount()).as("first report attempted").isEqualTo(1);
        assertThat(awaitingAcceptanceExchangeIds()).as("surfaced after a failed report").contains(exchangeId);

        // 4. Re-drive with the same verdict: delivery succeeds, so the flag clears and reconciliation stops surfacing it.
        assertThat(accept(exchangeId, "ACCEPTED").statusCode()).isEqualTo(202);
        assertThat(provider.getRequestCount()).as("re-drive re-reports even for the same verdict").isEqualTo(2);
        assertThat(awaitingAcceptanceExchangeIds()).as("cleared after a delivered report").doesNotContain(exchangeId);
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
