package org.metaform.certo.common.http;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RetryingHttpClient}: retry a transient {@code IOException}/{@code 5xx}; never a success or 4xx. */
class RetryingHttpClientTest {

    private MockWebServer server;
    private RetryingHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // 2 retries (3 attempts total), 1ms–5ms backoff so the test stays fast.
        var properties = new HttpClientProperties(2, 2, 2, 5, 2, 1L, 5L);
        client = new RetryingHttpClient(new OkHttpClient(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private Request request() {
        return new Request.Builder().url(server.url("/")).get().build();
    }

    @Test
    void success_isNotRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        try (var response = client.execute(request())) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void clientError_isNotRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        try (var response = client.execute(request())) {
            assertThat(response.code()).isEqualTo(404);
        }
        assertThat(server.getRequestCount()).isEqualTo(1);   // 4xx is a definitive answer, not retried
    }

    @Test
    void serverError_isRetried_thenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200));
        try (var response = client.execute(request())) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void serverError_whenPersistent_returnsTheLastResponseAfterExhausting() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        try (var response = client.execute(request())) {
            assertThat(response.code()).isEqualTo(503);   // exhausted result-failure is returned, not thrown
        }
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void transportError_whenPersistent_throwsIOExceptionAfterExhausting() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThatThrownBy(() -> client.execute(request())).isInstanceOf(IOException.class);
    }
}
