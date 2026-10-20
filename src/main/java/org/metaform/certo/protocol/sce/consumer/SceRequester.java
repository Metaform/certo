package org.metaform.certo.protocol.sce.consumer;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.protocol.sce.Sce;
import org.metaform.certo.protocol.sce.SceCertificateCodec;
import org.metaform.certo.protocol.sce.model.SceExchange;
import org.metaform.certo.protocol.spi.ProtocolRequester;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Opens and polls certificate requests on a Certificate Provider's data plane using OkHttp — the consumer-initiated "pull" half of the protocol. The provider endpoint comes
 * from the siglet cache (per flow).
 */
@Component
public class SceRequester implements ProtocolRequester {

    @Override
    public String protocolId() {
        return Sce.PROTOCOL_ID;
    }


    private static final MediaType JSON = MediaType.get("application/json");

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokenCache outboundTokenCache;

    public SceRequester(RetryingHttpClient httpClient, ObjectMapper mapper, OutboundTokenCache outboundTokenCache) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokenCache = outboundTokenCache;
    }

    /** Opens a certificate request and returns the opened exchange's identity and fulfillment status. */
    public ProviderRequestResult request(String messageId, String certificateType, List<LocationFilter> certifiedLocations,
                                         OutboundCall call) throws IOException {
        var resolved = outboundTokenCache.forCall(call);
        var payload = (certifiedLocations == null || certifiedLocations.isEmpty())
                ? Map.<String, Object>of("messageId", messageId, "certificateType", certificateType)
                : Map.<String, Object>of("messageId", messageId, "certificateType", certificateType,
                        "certifiedLocations", certifiedLocations);
        var body = RequestBody.create(mapper.writeValueAsString(payload), JSON);
        var builder = new Request.Builder().url(url(resolved.baseUrl(), "exchanges")).post(body);
        OutboundJsonClient.authorize(builder, resolved.bearer());
        // Retry-safe: exchange creation is idempotent on (counterparty, messageId), so a retried send
        // returns the already-created exchange rather than opening a duplicate.
        try (Response response = http.execute(builder.build())) {
            return parse(response);
        }
    }

    /** Polls the fulfillment status of a previously opened exchange. */
    public ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException {
        var resolved = outboundTokenCache.forCall(call);
        var builder = new Request.Builder()
                .url(url(resolved.baseUrl(), "exchanges").newBuilder().addPathSegment(exchangeId).build())
                .get();
        OutboundJsonClient.authorize(builder, resolved.bearer());
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

    private ProviderRequestResult parse(Response response) throws IOException {
        var responseBody = response.body();
        var text = responseBody == null ? "" : responseBody.string();
        if (!response.isSuccessful()) {
            throw new IOException("Provider returned HTTP " + response.code() + ": " + text);
        }
        var wire = mapper.readValue(text, SceExchange.class);
        // The SCE Exchange object's single status spans both phases; for the consumer's fulfillment mirror,
        // an Acceptance-phase status implies the hand-off already happened (FULFILLED).
        FulfillmentStatus status;
        try {
            status = FulfillmentStatus.valueOf(wire.status());
        } catch (IllegalArgumentException acceptancePhase) {
            status = FulfillmentStatus.FULFILLED;
        }
        // The embedded certificate (full record at FULFILLED, reference form otherwise) through the codec.
        return new ProviderRequestResult(wire.exchangeId(), status,
                SceCertificateCodec.toDomain(wire.certificate()), wire.errors());
    }
}
