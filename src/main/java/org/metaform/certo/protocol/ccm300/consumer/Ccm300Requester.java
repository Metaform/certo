package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.metaform.certo.common.RetryingHttpClient;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.consumer.spi.CertificateRequester;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Opens and polls certificate requests on a Certificate Provider's data plane (CX-0135 &sect;4.4.1 /
 * &sect;4.4.2) using OkHttp — the consumer-initiated "pull" half of the protocol. The provider endpoint comes
 * from the siglet cache (per flow).
 */
@Component
public class Ccm300Requester implements CertificateRequester {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm300Requester.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokens outboundTokens;

    public Ccm300Requester(RetryingHttpClient httpClient, ObjectMapper mapper, OutboundTokens outboundTokens) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokens = outboundTokens;
    }

    /** Opens a certificate request and returns the opened exchange's identity and fulfillment status. */
    public ProviderRequestResult request(String certificateType, List<String> certifiedLocations, OutboundCall call)
            throws IOException {
        var resolved = outboundTokens.forCall(call);
        var payload = (certifiedLocations == null || certifiedLocations.isEmpty())
                ? Map.<String, Object>of("certificateType", certificateType)
                : Map.<String, Object>of("certificateType", certificateType, "certifiedLocations", certifiedLocations);
        var body = RequestBody.create(mapper.writeValueAsString(payload), JSON);
        var builder = new Request.Builder().url(url(resolved.baseUrl(), "certificate-requests")).post(body);
        authorize(builder, resolved.bearer());
        // Retry-safe: the provider reuses a still-live exchange for a repeated open (CX-0135 §2.1.1), so a
        // retried send returns the same exchange rather than opening a duplicate.
        try (Response response = http.execute(builder.build())) {
            return parse(response);
        }
    }

    /** Polls the fulfillment status of a previously opened exchange. */
    public ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException {
        var resolved = outboundTokens.forCall(call);
        var builder = new Request.Builder()
                .url(url(resolved.baseUrl(), "certificate-requests").newBuilder().addPathSegment(exchangeId).build())
                .get();
        authorize(builder, resolved.bearer());
        try (Response response = http.execute(builder.build())) {
            return parse(response);
        }
    }

    private static HttpUrl url(String baseUrl, String segment) throws IOException {
        var base = HttpUrl.parse(baseUrl);
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + baseUrl);
        }
        return base.newBuilder().addPathSegment(segment).build();
    }

    private static void authorize(Request.Builder builder, String bearer) {
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
    }

    private ProviderRequestResult parse(Response response) throws IOException {
        var responseBody = response.body();
        var text = responseBody == null ? "" : responseBody.string();
        if (!response.isSuccessful()) {
            throw new IOException("Provider returned HTTP " + response.code() + ": " + text);
        }
        return mapper.readValue(text, ProviderRequestResult.class);
    }
}
