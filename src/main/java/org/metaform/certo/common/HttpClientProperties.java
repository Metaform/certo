package org.metaform.certo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts and retry policy for the shared outbound {@link okhttp3.OkHttpClient} (all v3/v2.4.0 adapters,
 * routed through {@link RetryingHttpClient}). Timeout values are in seconds; a {@code 0} disables that
 * timeout (OkHttp's convention). The retry defaults mirror Eclipse Dataspace Components' {@code EdcHttpClient}
 * (5 retries, 500&nbsp;ms&ndash;10&nbsp;s exponential backoff).
 *
 * @param connectTimeoutSeconds TCP connect timeout (default {@code 10})
 * @param readTimeoutSeconds    socket read timeout (default {@code 30})
 * @param writeTimeoutSeconds   socket write timeout (default {@code 30})
 * @param callTimeoutSeconds    whole-call timeout, connect+write+read+redirects (default {@code 60})
 * @param maxRetries            retry attempts after the initial call, on a transient {@code IOException}
 *                              (default {@code 5})
 * @param minBackoffMillis      initial backoff between attempts, in milliseconds (default {@code 500})
 * @param maxBackoffMillis      maximum backoff between attempts, in milliseconds (default {@code 10000})
 */
@ConfigurationProperties(prefix = "certo.http")
public record HttpClientProperties(Integer connectTimeoutSeconds,
                                   Integer readTimeoutSeconds,
                                   Integer writeTimeoutSeconds,
                                   Integer callTimeoutSeconds,
                                   Integer maxRetries,
                                   Long minBackoffMillis,
                                   Long maxBackoffMillis) {

    public HttpClientProperties {
        connectTimeoutSeconds = connectTimeoutSeconds != null ? connectTimeoutSeconds : 10;
        readTimeoutSeconds = readTimeoutSeconds != null ? readTimeoutSeconds : 30;
        writeTimeoutSeconds = writeTimeoutSeconds != null ? writeTimeoutSeconds : 30;
        callTimeoutSeconds = callTimeoutSeconds != null ? callTimeoutSeconds : 60;
        maxRetries = maxRetries != null ? maxRetries : 5;
        minBackoffMillis = minBackoffMillis != null ? minBackoffMillis : 500L;
        maxBackoffMillis = maxBackoffMillis != null ? maxBackoffMillis : 10_000L;
    }
}
