package org.metaform.certo.protocol.ccm240.consumer;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateRequest;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestReply;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestStatus;
import org.metaform.certo.protocol.spi.ProtocolRequester;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opens a certificate request on a v2.4.0 provider ({@code POST /companycertificate/request}).
 *
 * <p><b>No exchange id on the wire.</b> A 2.4.0 provider assigns no {@code exchangeId} (2.4.0 has no exchange
 * concept), so this adapter mints a consumer-local surrogate per request and returns it as the exchange
 * identity. On {@code COMPLETED} the reply's {@code documentId} is the certificateId to pull.
 *
 * <p><b>No id-based poll.</b> 2.4.0 has no request-status query keyed by id — the "poll" is simply re-issuing
 * the request. {@link #pollStatus} therefore fails; a client refreshes status by re-opening the request (which the
 * provider deduplicates on its side, CX-0135 §2.1.1).
 */
@Component
public class Ccm240Requester implements ProtocolRequester {

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokenCache outboundTokenCache;
    private final Clock clock;

    public Ccm240Requester(RetryingHttpClient httpClient, ObjectMapper mapper, OutboundTokenCache outboundTokenCache, Clock clock) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokenCache = outboundTokenCache;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    @Override
    public ProviderRequestResult request(String certificateType, List<String> certifiedLocations, OutboundCall call) throws IOException {
        var resolved = outboundTokenCache.forCall(call);
        // certifiedBpn is the legal entity the certificate is about — the provider being asked (the counterparty).
        var header = new Ccm240Header(Ccm240Contexts.REQUEST, UUID.randomUUID().toString(), call.sender().bpn(), call.counterpartyBpn(), OffsetDateTime.now(clock).toString(), "3.1.0", null, null);
        var content = new Ccm240CertificateRequest.Content(call.counterpartyBpn(), certificateType, certifiedLocations);
        var body = RequestBody.create(mapper.writeValueAsString(new Ccm240CertificateRequest(header, content)), Ccm240OutboundClient.JSON);
        var builder = new Request.Builder().url(Ccm240OutboundClient.endpoint(resolved.baseUrl(), "request")).post(body);
        OutboundJsonClient.authorize(builder, resolved.bearer());

        // 2.4.0 carries no exchangeId; mint a consumer-local surrogate as the exchange identity.
        var exchangeId = UUID.randomUUID().toString();
        try (Response response = http.execute(builder.build())) {
            return parse(response, exchangeId);
        }
    }

    @Override
    public ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException {
        throw new IOException("v2.4.0 exposes no request-status poll (no request id); re-issue the request " + "(POST .../consumer/certificate-requests) to obtain the current status");
    }

    private ProviderRequestResult parse(Response response, String exchangeId) throws IOException {
        var responseBody = response.body();
        var text = responseBody == null ? "" : responseBody.string();
        // IN_PROGRESS is 202, COMPLETED / REJECTED are 200 — all 2xx; only a real error status is a failure.
        if (!response.isSuccessful()) {
            throw new IOException("Provider returned HTTP " + response.code() + " on v2.4.0 request: " + text);
        }
        var reply = mapper.readValue(text, Ccm240RequestReply.class);
        if (reply.requestStatus() == null) {
            throw new IOException("v2.4.0 request reply is missing requestStatus: " + text);
        }
        var status = Ccm240Translation.toFulfillmentStatus(Ccm240RequestStatus.valueOf(reply.requestStatus()));
        var certificateId = status == FulfillmentStatus.FULFILLED ? reply.documentId() : null;
        return new ProviderRequestResult(exchangeId, certificateId, null, status, toStatusErrors(reply));
    }

    /**
     * Folds a rejection's {@code requestErrors} + per-location {@code locationErrors} into neutral errors.
     */
    private static List<StatusError> toStatusErrors(Ccm240RequestReply reply) {
        var errors = new ArrayList<StatusError>();
        if (reply.requestErrors() != null) {
            reply.requestErrors().forEach(e -> errors.add(new StatusError(e.message())));
        }
        if (reply.locationErrors() != null) {
            for (var location : reply.locationErrors()) {
                if (location.locationErrors() != null) {
                    location.locationErrors().forEach(e -> errors.add(new StatusError(e.message(), location.bpn())));
                }
            }
        }
        return errors.isEmpty() ? null : errors;
    }
}
