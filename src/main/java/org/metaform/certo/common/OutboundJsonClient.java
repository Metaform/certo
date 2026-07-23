package org.metaform.certo.common;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * The shared best-effort outbound JSON POST used by the notification/report adapters (v3 and v2.4.0):
 * serialize a payload to JSON, POST it (with an optional bearer token) over the retrying HTTP client, and
 * return {@code true} on a {@code 2xx}. Every failure — an unserializable payload, a malformed URL, a non-2xx
 * response, or a transport error — is logged with the caller's {@code context} and returns {@code false}; the
 * core has already recorded its state locally, so delivery is never allowed to throw. Centralizes the
 * serialize / URL-guard / bearer-header / execute / log boilerplate the adapters used to each repeat.
 */
@Component
public class OutboundJsonClient {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundJsonClient.class);

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;

    public OutboundJsonClient(RetryingHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /** Best-effort POST of {@code payload} to {@code baseUrl} with a single appended {@code pathSegment}. */
    public boolean postTo(String baseUrl, String pathSegment, Object payload, MediaType contentType,
                          String bearer, String context) {
        var base = HttpUrl.parse(baseUrl);
        if (base == null) {
            LOG.warn("{}: invalid base URL '{}'; not delivering", context, baseUrl);
            return false;
        }
        return post(base.newBuilder().addPathSegment(pathSegment).build(), payload, contentType, bearer, context);
    }

    /** Best-effort POST of {@code payload} to a fully-formed {@code url}. */
    public boolean postToUrl(String url, Object payload, MediaType contentType, String bearer, String context) {
        var parsed = HttpUrl.parse(url);
        if (parsed == null) {
            LOG.warn("{}: invalid URL '{}'; not delivering", context, url);
            return false;
        }
        return post(parsed, payload, contentType, bearer, context);
    }

    private boolean post(HttpUrl url, Object payload, MediaType contentType, String bearer, String context) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            LOG.warn("{}: could not serialize payload: {}", context, e.getMessage());
            return false;
        }
        var builder = new Request.Builder().url(url).post(RequestBody.create(json, contentType));
        authorize(builder, bearer);
        try (var response = http.execute(builder.build())) {
            if (response.isSuccessful()) {
                return true;
            }
            LOG.warn("{}: peer returned HTTP {}", context, response.code());
            return false;
        } catch (IOException e) {
            LOG.warn("{}: delivery failed: {}", context, e.getMessage());
            return false;
        }
    }

    /** Attaches a {@code Authorization: Bearer} header when a token is supplied (shared with the pull adapters). */
    public static void authorize(Request.Builder builder, String bearer) {
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
    }
}
