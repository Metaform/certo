package org.metaform.certo.protocol.ccm300.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.metaform.certo.common.OutboundJsonClient;
import org.metaform.certo.common.RetryingHttpClient;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.provider.spi.AcceptancePoller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The v3 {@link AcceptancePoller}: GETs the consumer's
 * {@code /certificate-acceptance-status/{exchangeId}} (CX-0135 &sect;4.3.3), with the token + endpoint
 * resolved from the siglet cache for the given flow. A {@code 404} means the consumer has not decided yet.
 */
@Component
public class Ccm300AcceptancePoller implements AcceptancePoller {

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokens outboundTokens;

    public Ccm300AcceptancePoller(RetryingHttpClient http, ObjectMapper mapper, OutboundTokens outboundTokens) {
        this.http = http;
        this.mapper = mapper;
        this.outboundTokens = outboundTokens;
    }

    @Override
    public Optional<AcceptanceStatusData> pollAcceptance(String exchangeId, OutboundCall call) throws IOException {
        var resolved = outboundTokens.forCall(call);
        var base = HttpUrl.parse(resolved.baseUrl());
        if (base == null) {
            throw new IOException("Invalid consumer base URL: " + resolved.baseUrl());
        }
        var url = base.newBuilder().addPathSegment("certificate-acceptance-status").addPathSegment(exchangeId).build();
        var builder = new Request.Builder().url(url).get();
        OutboundJsonClient.authorize(builder, resolved.bearer());
        try (var response = http.execute(builder.build())) {
            if (response.code() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();   // the consumer has not recorded an acceptance yet
            }
            var body = response.body();
            var text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("Consumer acceptance-status returned HTTP " + response.code() + ": " + text);
            }
            var parsed = mapper.readValue(text, AcceptanceStatusResponse.class);
            if (parsed.status() == null) {
                return Optional.empty();
            }
            return Optional.of(new AcceptanceStatusData(parsed.exchangeId(), parsed.certificateId(),
                    parsed.status(), parsed.errors()));
        }
    }

    /** Mirrors the consumer's {@code CertificateAcceptanceStatusResponse} (only the fields we consume). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AcceptanceStatusResponse(String exchangeId, String certificateId,
                                            AcceptanceStatus status, List<StatusError> errors) {
    }
}
