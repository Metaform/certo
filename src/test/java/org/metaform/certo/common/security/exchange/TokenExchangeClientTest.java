package org.metaform.certo.common.security.exchange;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metaform.certo.common.http.HttpClientProperties;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.security.SecurityProperties;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TokenExchangeClient}: the RFC 8693 exchange that authenticates certo's calls to siglet — off by
 * default, and when on, form-encoded with the ServiceAccount token as {@code subject_token}.
 */
class TokenExchangeClientTest {

    private static final String SUBJECT_TOKEN = "k8s.sa.jwt";

    @TempDir
    Path tempDir;

    private MockWebServer broker;
    private Path subjectTokenFile;

    @BeforeEach
    void setUp() throws Exception {
        broker = new MockWebServer();
        broker.start();
        subjectTokenFile = tempDir.resolve("token");
        Files.writeString(subjectTokenFile, SUBJECT_TOKEN + "\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        broker.shutdown();
    }

    private TokenExchangeClient client(SecurityProperties.TokenExchange config) {
        var http = new RetryingHttpClient(new OkHttpClient(), new HttpClientProperties(2, 2, 2, 5, 0, 1L, 5L));
        return new TokenExchangeClient(new SecurityProperties("http://siglet", config), http,
                JsonMapper.builder().build());
    }

    private SecurityProperties.TokenExchange enabled() {
        return new SecurityProperties.TokenExchange(true, broker.url("/token").toString(),
                "siglet:read siglet:verify", "did:web:siglet", "verify-context",
                subjectTokenFile.toString());
    }

    /** Decodes a form-encoded body by parsing it as a query string. */
    private static String field(String body, String name) {
        return HttpUrl.parse("http://form/?" + body).queryParameter(name);
    }

    /** The body may only be drained once, so every assertion reads from this snapshot. */
    private static String bodyOf(RecordedRequest request) {
        return request.getBody().readUtf8();
    }

    @Test
    void disabled_returnsEmpty_andCallsNoBroker() {
        var client = client(new SecurityProperties.TokenExchange(false, null, null, null, null, null));

        assertThat(client.enabled()).isFalse();
        assertThat(client.accessTokenFor("some-context")).isEmpty();
        assertThat(broker.getRequestCount()).isZero();
    }

    @Test
    void enabled_sendsRfc8693Exchange_andReturnsTheAccessToken() throws Exception {
        broker.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"access_token":"exchanged.jwt","issued_token_type":"urn:ietf:params:oauth:token-type:jwt",
                         "token_type":"Bearer","expires_in":3600}"""));

        var token = client(enabled()).accessTokenFor("provider-context");

        assertThat(token).contains("exchanged.jwt");
        var request = broker.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/token");
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        var body = bodyOf(request);
        assertThat(field(body, "grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(field(body, "subject_token")).isEqualTo(SUBJECT_TOKEN);   // trailing newline stripped
        assertThat(field(body, "subject_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
        assertThat(field(body, "resource")).isEqualTo("provider-context");
        assertThat(field(body, "scope")).isEqualTo("siglet:read siglet:verify");
        assertThat(field(body, "audience")).isEqualTo("did:web:siglet");
    }

    @Test
    void subjectToken_isReReadOnEveryExchange() throws Exception {
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"a\"}"));
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"b\"}"));
        var client = client(enabled());

        client.accessTokenFor("ctx");
        // The kubelet rotates a projected ServiceAccount token in place; nothing may be cached.
        Files.writeString(subjectTokenFile, "rotated.sa.jwt");
        client.accessTokenFor("ctx");

        broker.takeRequest();
        assertThat(field(bodyOf(broker.takeRequest()), "subject_token")).isEqualTo("rotated.sa.jwt");
    }

    @Test
    void brokerRejection_isABadGateway_withoutLeakingTheBrokerHost() {
        broker.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client(enabled()).accessTokenFor("ctx"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining(broker.getHostName() + ":" + broker.getPort());
    }

    @Test
    void missingAccessToken_isABadGateway() {
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{\"token_type\":\"Bearer\"}"));

        assertThatThrownBy(() -> client(enabled()).accessTokenFor("ctx"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("missing an access_token");
    }

    @Test
    void unreadableSubjectToken_isABadGateway_withoutLeakingThePath() {
        var missing = tempDir.resolve("absent/token");
        var config = new SecurityProperties.TokenExchange(true, broker.url("/token").toString(),
                "scope", "aud", "verify-context", missing.toString());

        assertThatThrownBy(() -> client(config).accessTokenFor("ctx"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageNotContaining(missing.toString());
        assertThat(broker.getRequestCount()).isZero();
    }

    @Test
    void enabledWithoutRequiredConfig_failsFast() {
        assertThatThrownBy(() -> client(new SecurityProperties.TokenExchange(true, null, "scope", "aud",
                "verify-context", "/tmp/token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("certo.security.token-exchange.url");

        assertThatThrownBy(() -> client(new SecurityProperties.TokenExchange(true, "http://broker/token",
                "scope", "aud", " ", "/tmp/token")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("certo.security.token-exchange.verify-resource");
    }
}
