package org.metaform.certo.common.security;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9 under security: a consumer&rarr;provider outbound call (here the consumer-initiated request) resolves
 * its token + endpoint from the stubbed siglet cache and reaches the provider at that endpoint with an
 * {@code Authorization: Bearer} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18087",
        "certo.security.siglet-base-url=http://localhost:18101"
})
class SecurityConsumerOutboundTest {

    private static final String BASE = "http://localhost:18087";
    private static final String TOKEN = "consumer-out-token";

    private final HttpClient http = HttpClient.newHttpClient();
    private MockWebServer siglet;
    private MockWebServer provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new MockWebServer();
        provider.start();
        provider.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"exchangeId\":\"exch-out-1\",\"status\":\"CERTIFICATION_REQUESTED\"}"));
        var providerBase = provider.url("/").toString();

        siglet = new MockWebServer();
        siglet.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/tokens/")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"token\":\"" + TOKEN + "\",\"endpoint\":\"" + providerBase + "\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        siglet.start(18101);
    }

    @AfterEach
    void tearDown() throws Exception {
        siglet.shutdown();
        provider.shutdown();
    }

    @Test
    void consumerRequest_carriesBearerToProviderEndpoint() throws Exception {
        var response = post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/certificate-requests",
                "{\"providerBpn\":\"BPNL0000000001AB\",\"providerDid\":\"did:web:provider\","
                        + "\"certificateType\":\"ISO9001\",\"flowId\":\"flow-1\"}");
        assertThat(response.statusCode()).isEqualTo(202);

        var recorded = provider.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/certificate-requests");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
