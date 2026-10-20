package org.metaform.certo.protocol.sce.consumer;

import okhttp3.MediaType;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolAcceptanceReporter;
import org.metaform.certo.protocol.sce.Sce;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reports the consumer's Acceptance-phase outcome back to the Certificate Provider by POSTing a
 * {@code CertificateAcceptanceStatus} CloudEvent to {@code /certificate-acceptance-notifications}
 *, closing the exchange loop.
 *
 * <p>Best-effort (via {@link OutboundJsonClient}): a failure to deliver (transport error, or the provider not
 * recognizing the exchange) is logged but does not disrupt the consumer's own processing — the consumer has
 * already recorded its decision locally.
 */
@Component
public class SceReporter implements ProtocolAcceptanceReporter {

    @Override
    public String protocolId() {
        return Sce.PROTOCOL_ID;
    }


    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CloudEvent.CONTENT_TYPE_CLOUDEVENTS);

    private final OutboundJsonClient outbound;
    private final OutboundTokenCache outboundTokenCache;
    private final Clock clock;

    public SceReporter(OutboundJsonClient outbound, OutboundTokenCache outboundTokenCache, Clock clock) {
        this.outbound = outbound;
        this.outboundTokenCache = outboundTokenCache;
        this.clock = clock;
    }

    /**
     * Sends an acceptance status event for the given exchange to the provider (best-effort). Returns
     * {@code true} on successful delivery.
     */
    @Override
    public boolean report(ExchangeBinding binding, String exchangeId,
                          AcceptanceStatus status, List<StatusError> errors, OutboundCall call) {
        var data = new AcceptanceStatusData(exchangeId, status, errors);
        var event = new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                Sce.TYPE_ACCEPTANCE_STATUS,
                // Source = the asserting consumer's identity (DID); subject = the exchangeId (SCE).
                call.sender().did(),
                exchangeId,
                // Deterministic id per (exchange, verdict), so a re-report (e.g. reconciling a lost report) is
                // deduplicated by the provider on source+id rather than re-applied. The
                // verdict get distinct ids (both are legitimate events for the exchange).
                acceptanceEventId(exchangeId, status),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                null,
                call.sender().dataspaceIdentifier(),
                data);

        var resolved = outboundTokenCache.forCall(call);
        return outbound.postTo(resolved.baseUrl(), "notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "report acceptance " + status + " for exchange " + exchangeId);
    }

    /** A stable CloudEvent id for an acceptance event, so a re-report of the same (exchange, verdict) dedups. */
    private static String acceptanceEventId(String exchangeId, AcceptanceStatus status) {
        return UUID.nameUUIDFromBytes((exchangeId + "|" + status).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
