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
 * P4 outbound: with {@code certo.security.enabled=true}, a provider publish carries a {@code flowId}; the
 * adapter resolves the token + endpoint from the (stubbed) siglet cache and delivers the push to <b>that
 * endpoint</b> with an {@code Authorization: Bearer} header — proving the counterparty URL comes from the
 * token, not configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18086",
        "certo.security.siglet-base-url=http://localhost:18100"
})
class SecurityOutboundTest {

    private static final String BASE = "http://localhost:18086";
    private static final String TOKEN = "test-outbound-token";

    private final HttpClient http = HttpClient.newHttpClient();
    private MockWebServer siglet;
    private MockWebServer targetConsumer;

    @BeforeEach
    void setUp() throws Exception {
        targetConsumer = new MockWebServer();
        targetConsumer.start();
        targetConsumer.enqueue(new MockResponse().setResponseCode(200));
        var consumerBase = targetConsumer.url("/").toString();

        siglet = new MockWebServer();
        siglet.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/tokens/")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"token\":\"" + TOKEN + "\",\"endpoint\":\"" + consumerBase + "\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        siglet.start(18100);
    }

    @AfterEach
    void tearDown() throws Exception {
        siglet.shutdown();
        targetConsumer.shutdown();
    }

    @Test
    void publish_deliversToTokenEndpointWithBearer() throws Exception {
        var publish = post("/management/v1/participant-contexts/pctx-seed-provider/certificates/00000000-0000-0000-0000-000000009001/publish",
                "{\"protocolVersion\":\"3.0.0\","
                        + "\"consumerBpn\":\"BPNL0000000002CD\",\"consumerDid\":\"did:web:consumer\",\"flowId\":\"flow-1\"}");
        assertThat(publish.statusCode()).isEqualTo(202);

        var pushed = targetConsumer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(pushed).isNotNull();
        assertThat(pushed.getPath()).isEqualTo("/certificate-notifications");
        assertThat(pushed.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void publish_withoutFlowId_isBadRequest() throws Exception {
        assertThat(post("/management/v1/participant-contexts/pctx-seed-provider/certificates/00000000-0000-0000-0000-000000009001/publish", "{}").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
