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
 * <p><b>Dataspace shortcut.</b> {@code url} here is treated as a direct <b>data-plane</b> endpoint that we
 * POST straight to. In a real Catena-X deployment the peer advertises a <b>control-plane</b> DSP endpoint
 * (e.g. {@code .../edc/api/v1/dsp}); the actual data-plane URL (plus its authorization token) is not known
 * up front and must be determined <b>out-of-band</b>: query that DSP endpoint's catalog for the peer's
 * notification-receiver asset, negotiate a contract, start a transfer, and use the resulting EDR (Endpoint
 * Data Reference). That whole control-plane exchange is out of scope here, so we collapse it and assume the
 * supplied URL is already the data-plane target.
 */
@Component
public class Ccm240OutboundClient {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240OutboundClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper;

    public Ccm240OutboundClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * POSTs {@code message} as JSON to {@code url}. Returns {@code true} on a 2xx response.
     *
     * @param url the peer's <b>data-plane</b> endpoint. See the class note: in production this is resolved
     *            out-of-band from the peer's DSP control-plane endpoint via catalog + contract + transfer
     *            (EDR); here it is assumed to already be the data-plane URL.
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
