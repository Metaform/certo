package org.metaform.certo.common;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * The single outbound HTTP entry point: executes an OkHttp {@link Request} on the shared
 * {@link OkHttpClient} wrapped in a Failsafe {@link RetryPolicy} — the same retry library Eclipse Dataspace
 * Components uses for its {@code EdcHttpClient}. A transient {@link IOException} or {@code 5xx} response is
 * retried with exponential backoff; a completed non-{@code 5xx} response is returned as-is.
 *
 * <p>Retrying is safe because the outbound calls are idempotent:
 * <ul>
 *   <li>reads (GET);</li>
 *   <li>the notification and acceptance messages, which carry an {@code exchangeId} the receiver treats as
 *       referring to the same exchange (CX-0135 &sect;2.1.1) — this runtime additionally dedups the CloudEvent
 *       by {@code source+id};</li>
 *   <li>the consumer&rarr;provider request-open, which the provider resolves as a find-or-create over a
 *       still-live exchange (CX-0135 &sect;2.1.1) rather than opening a duplicate.</li>
 * </ul>
 * Retry policy is configured in one place ({@code certo.http.*}, see {@link HttpClientProperties}).
 */
@Component
public class RetryingHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingHttpClient.class);

    private final OkHttpClient http;
    private final RetryPolicy<Response> retryPolicy;

    public RetryingHttpClient(OkHttpClient http, HttpClientProperties properties) {
        this.http = http;
        this.retryPolicy = RetryPolicy.<Response>builder()
                .handle(IOException.class)
                // A 5xx is a transient server failure; retry it (only reached by the idempotent execute path).
                .handleResultIf(response -> response.code() >= 500)
                .withMaxRetries(properties.maxRetries())
                .withBackoff(Duration.ofMillis(properties.minBackoffMillis()),
                        Duration.ofMillis(properties.maxBackoffMillis()))
                // A retried 5xx response is discarded; close it so its connection is not leaked. An IOException
                // attempt has no response, so getLastResult() is null there.
                .onRetry(event -> {
                    var last = event.getLastResult();
                    if (last != null) {
                        last.close();
                    }
                })
                .onFailedAttempt(event -> LOG.debug("Outbound HTTP attempt {} failed: {}",
                        event.getAttemptCount(),
                        event.getLastException() != null ? event.getLastException().getMessage()
                                : "HTTP " + (event.getLastResult() != null ? event.getLastResult().code() : "?")))
                .build();
    }

    /**
     * Executes the request, retrying a transient {@link IOException} or {@code 5xx} response. Returns the
     * (open) {@link Response} — the caller owns it and must close it (e.g. try-with-resources), exactly as with
     * {@code okHttpClient.newCall(request).execute()}. Throws the last {@link IOException} once retries are
     * exhausted; a persistent {@code 5xx} is returned as-is for the caller to handle.
     */
    public Response execute(Request request) throws IOException {
        try {
            return Failsafe.with(retryPolicy).get(() -> http.newCall(request).execute());
        } catch (FailsafeException e) {
            // Failsafe wraps the terminal checked exception; unwrap the original IOException for callers.
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }
}
