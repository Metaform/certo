package org.metaform.certo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts for the shared outbound {@link okhttp3.OkHttpClient} (all v3/v2.4.0 adapters). Each value is in
 * seconds; a {@code 0} disables that timeout (OkHttp's convention). Defaults match the previous hardcoded
 * values.
 *
 * @param connectTimeoutSeconds TCP connect timeout (default {@code 10})
 * @param readTimeoutSeconds    socket read timeout (default {@code 30})
 * @param writeTimeoutSeconds   socket write timeout (default {@code 30})
 * @param callTimeoutSeconds    whole-call timeout, connect+write+read+redirects (default {@code 60})
 */
@ConfigurationProperties(prefix = "certo.http")
public record HttpClientProperties(Integer connectTimeoutSeconds,
                                   Integer readTimeoutSeconds,
                                   Integer writeTimeoutSeconds,
                                   Integer callTimeoutSeconds) {

    public HttpClientProperties {
        connectTimeoutSeconds = connectTimeoutSeconds != null ? connectTimeoutSeconds : 10;
        readTimeoutSeconds = readTimeoutSeconds != null ? readTimeoutSeconds : 30;
        writeTimeoutSeconds = writeTimeoutSeconds != null ? writeTimeoutSeconds : 30;
        callTimeoutSeconds = callTimeoutSeconds != null ? callTimeoutSeconds : 60;
    }
}
