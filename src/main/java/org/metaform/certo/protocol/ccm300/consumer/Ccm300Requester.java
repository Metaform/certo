package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.metaform.certo.common.CertoProperties;
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
 * &sect;4.4.2) using OkHttp — the consumer-initiated "pull" half of the protocol. The provider base URL
 * is hardcoded via {@code certo.provider-base-url}.
 */
@Component
public class Ccm300Requester implements CertificateRequester {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm300Requester.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String providerBaseUrl;

    public Ccm300Requester(OkHttpClient httpClient, ObjectMapper mapper, CertoProperties properties) {
        this.http = httpClient;
        this.mapper = mapper;
        this.providerBaseUrl = properties.providerBaseUrl();
    }

    /** Opens a certificate request and returns the opened exchange's identity and fulfillment status. */
    public ProviderRequestResult request(String certificateType, List<String> certifiedLocations) throws IOException {
        var payload = (certifiedLocations == null || certifiedLocations.isEmpty())
                ? Map.<String, Object>of("certificateType", certificateType)
                : Map.<String, Object>of("certificateType", certificateType, "certifiedLocations", certifiedLocations);
        var body = RequestBody.create(mapper.writeValueAsString(payload), JSON);
        var request = new Request.Builder()
                .url(url("certificate-requests"))
                .post(body)
                .build();
        return send(request);
    }

    /** Polls the fulfillment status of a previously opened exchange. */
    public ProviderRequestResult pollStatus(String exchangeId) throws IOException {
        var request = new Request.Builder()
                .url(url("certificate-requests").newBuilder().addPathSegment(exchangeId).build())
                .get()
                .build();
        return send(request);
    }

    private HttpUrl url(String segment) throws IOException {
        var base = HttpUrl.parse(providerBaseUrl);
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + providerBaseUrl);
        }
        return base.newBuilder().addPathSegment(segment).build();
    }

    private ProviderRequestResult send(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            var responseBody = response.body();
            var text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code() + ": " + text);
            }
            return mapper.readValue(text, ProviderRequestResult.class);
        }
    }
}
