package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolAcceptanceReporter;
import org.metaform.certo.protocol.ProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import static java.util.UUID.randomUUID;

/**
 * Reports the consumer's Acceptance-phase outcome back to the Certificate Provider by POSTing a
 * {@code CertificateAcceptanceStatus} CloudEvent to {@code /certificate-acceptance-notifications}
 * (CX-0135 &sect;4.4.4), closing the exchange loop.
 *
 * <p>Best-effort: a failure to deliver (transport error, or the provider not recognizing the
 * exchange) is logged but does not disrupt the consumer's own processing — the consumer has already
 * recorded its decision locally.
 */
@Component
public class Ccm300Reporter implements ProtocolAcceptanceReporter {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm300Reporter.class);
    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokens outboundTokens;
    private final Clock clock;

    public Ccm300Reporter(OkHttpClient httpClient, ObjectMapper mapper,
                          OutboundTokens outboundTokens, Clock clock) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokens = outboundTokens;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_3_0_0;
    }

    /**
     * Sends an acceptance status event for the given exchange to the provider (best-effort).
     */
    @Override
    public void report(ExchangeBinding binding, String exchangeId, String certificateId,
                       AcceptanceStatus status, List<StatusError> errors, OutboundCall call) {
        var data = new AcceptanceStatusData(exchangeId, certificateId, status, errors);
        var event = new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                CcmEvents.TYPE_ACCEPTANCE_STATUS,
                call.sender().source(),
                call.counterpartyBpn(),
                randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                CcmEvents.SCHEMA_ACCEPTANCE_STATUS,
                call.sender().bpn(),
                data);

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize acceptance event for exchange {}: {}", exchangeId, e.getMessage());
            return;
        }

        var resolved = outboundTokens.forCall(call);
        var base = HttpUrl.parse(resolved.baseUrl());
        if (base == null) {
            LOG.warn("Invalid provider base URL '{}'; not reporting acceptance for exchange {}",
                    resolved.baseUrl(), exchangeId);
            return;
        }
        var url = base.newBuilder().addPathSegment("certificate-acceptance-notifications").build();

        var builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, CLOUDEVENTS_JSON));
        if (resolved.bearer() != null) {
            builder.header("Authorization", "Bearer " + resolved.bearer());
        }
        var request = builder.build();

        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOG.warn("Provider did not accept the acceptance report for exchange {}: HTTP {}",
                        exchangeId, response.code());
            }
        } catch (IOException e) {
            LOG.warn("Failed to report acceptance for exchange {}: {}", exchangeId, e.getMessage());
        }
    }
}
