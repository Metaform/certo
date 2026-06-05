package org.metaform.certo.consumer.client;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
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
public class ProviderAcceptanceClient {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderAcceptanceClient.class);
    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper;
    private final CertoProperties properties;
    private final Clock clock;

    public ProviderAcceptanceClient(ObjectMapper mapper, CertoProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Sends an acceptance status event for the given exchange to the provider (best-effort). */
    public void report(String exchangeId, String certificateId, AcceptanceStatus status, List<StatusError> errors) {
        var data = new AcceptanceStatusData(exchangeId, certificateId, status, errors);
        var event = new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                CcmEvents.TYPE_ACCEPTANCE_STATUS,
                properties.consumer().source(),
                properties.provider().bpn(),
                randomUUID().toString(),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                CcmEvents.SCHEMA_ACCEPTANCE_STATUS,
                properties.consumer().bpn(),
                data);

        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            LOG.warn("Could not serialize acceptance event for exchange {}: {}", exchangeId, e.getMessage());
            return;
        }

        var base = HttpUrl.parse(properties.providerBaseUrl());
        if (base == null) {
            LOG.warn("Invalid provider base URL '{}'; not reporting acceptance for exchange {}",
                    properties.providerBaseUrl(), exchangeId);
            return;
        }
        var url = base.newBuilder().addPathSegment("certificate-acceptance-notifications").build();

        var request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, CLOUDEVENTS_JSON))
                .build();

        try (var response = http.newCall(request).execute()) {
            if (response.isSuccessful()) {
                LOG.info("Reported acceptance {} for exchange {} to provider (HTTP {})",
                        status, exchangeId, response.code());
            } else {
                LOG.warn("Provider did not accept the acceptance report for exchange {}: HTTP {}",
                        exchangeId, response.code());
            }
        } catch (IOException e) {
            LOG.warn("Failed to report acceptance for exchange {}: {}", exchangeId, e.getMessage());
        }
    }
}
