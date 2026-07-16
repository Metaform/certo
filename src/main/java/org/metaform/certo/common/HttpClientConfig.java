package org.metaform.certo.common;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Supplies the single, shared {@link OkHttpClient} injected into every outbound HTTP client — the v3
 * {@code Ccm300*} adapters and the v2.4.0 {@code Ccm240OutboundClient}. An {@code OkHttpClient} owns a
 * connection pool and a dispatcher thread pool and is designed to be shared, so the whole runtime uses one
 * configured instance rather than constructing a fresh (unshared) client per collaborator. Timeouts are
 * configurable via {@code certo.http.*} ({@link HttpClientProperties}).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient(HttpClientProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(properties.writeTimeoutSeconds()))
                .callTimeout(Duration.ofSeconds(properties.callTimeoutSeconds()))
                .build();
    }
}
