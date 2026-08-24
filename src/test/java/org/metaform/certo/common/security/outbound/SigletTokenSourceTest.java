package org.metaform.certo.common.security.outbound;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metaform.certo.common.http.HttpClientProperties;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.security.SecurityProperties;
import org.metaform.certo.common.security.exchange.TokenExchangeClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SigletTokenSource} over real HTTP: the outbound token lookup, and the token exchange that
 * authenticates it — whose {@code resource} is the <em>calling</em> participant context.
 */
class SigletTokenSourceTest {

    @TempDir
    Path tempDir;

    private MockWebServer siglet;
    private MockWebServer broker;
    private RetryingHttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        siglet = new MockWebServer();
        siglet.start();
        broker = new MockWebServer();
        broker.start();
        http = new RetryingHttpClient(new OkHttpClient(), new HttpClientProperties(2, 2, 2, 5, 0, 1L, 5L));
    }

    @AfterEach
    void tearDown() throws Exception {
        siglet.shutdown();
        broker.shutdown();
    }

    private SigletTokenSource source(SecurityProperties.TokenExchange exchange) {
        var mapper = JsonMapper.builder().build();
        var properties = new SecurityProperties(siglet.url("/").toString(), exchange);
        return new SigletTokenSource(http, mapper, properties,
                new TokenExchangeClient(properties, http, mapper));
    }

    @Test
    void exchangeDisabled_callsSigletUnauthenticated() throws Exception {
        siglet.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"token\":\"siglet.jwt\",\"endpoint\":\"http://counterparty\"}"));

        var resolved = source(new SecurityProperties.TokenExchange(false, null, null, null, null, null))
                .resolve("provider-context", "did:web:consumer", "flow-1");

        assertThat(resolved).isEqualTo(new ResolvedToken("siglet.jwt", "http://counterparty"));
        var request = siglet.takeRequest();
        assertThat(request.getPath()).isEqualTo("/tokens/provider-context/flow-1");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(broker.getRequestCount()).isZero();
    }

    @Test
    void exchangeEnabled_exchangesForTheCallersContext_thenAuthenticatesTheLookup() throws Exception {
        var subjectToken = tempDir.resolve("token");
        Files.writeString(subjectToken, "k8s.sa.jwt");
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"exchanged.jwt\"}"));
        siglet.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"token\":\"siglet.jwt\",\"endpoint\":\"http://counterparty\"}"));

        var exchange = new SecurityProperties.TokenExchange(true, broker.url("/token").toString(),
                "siglet:read", "did:web:siglet", "verify-context", subjectToken.toString());
        var resolved = source(exchange).resolve("provider-context", "did:web:consumer", "flow-1");

        assertThat(resolved.bearerToken()).isEqualTo("siglet.jwt");
        // The outbound lookup is per-tenant, so the exchange is scoped to that tenant.
        var exchangeBody = broker.takeRequest().getBody().readUtf8();
        assertThat(HttpUrl.parse("http://form/?" + exchangeBody).queryParameter("resource"))
                .isEqualTo("provider-context");
        assertThat(siglet.takeRequest().getHeader("Authorization")).isEqualTo("Bearer exchanged.jwt");
    }
}
