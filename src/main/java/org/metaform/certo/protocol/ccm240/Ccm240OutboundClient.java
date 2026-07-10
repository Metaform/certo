package org.metaform.certo.protocol.ccm240;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Sends legacy v2.4.0 JSON messages to a peer's endpoint (Phase 4 outbound). Best-effort: transport or
 * non-2xx failures are logged, not thrown — the v3 core has already recorded its state locally.
 *
 * <p>{@code url} is POSTed to directly as the peer's endpoint.
 */
@Component
public class Ccm240OutboundClient {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240OutboundClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public Ccm240OutboundClient(OkHttpClient httpClient, ObjectMapper mapper) {
        this.http = httpClient;
        this.mapper = mapper;
    }

    /**
     * POSTs {@code message} as JSON to {@code url}. Returns {@code true} on a 2xx response.
     *
     * @param url the peer's endpoint, POSTed to directly.
     */
    public boolean post(String url, Object message) {
        String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize legacy message for {}: {}", url, e.getMessage());
            return false;
        }
        var request = new Request.Builder().url(url).post(RequestBody.create(json, JSON)).build();
        try (var response = http.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            LOG.warn("Ccm240 peer at {} returned HTTP {}", url, response.code());
            return false;
        } catch (IOException e) {
            LOG.warn("Failed to deliver legacy message to {}: {}", url, e.getMessage());
            return false;
        }
    }
}
