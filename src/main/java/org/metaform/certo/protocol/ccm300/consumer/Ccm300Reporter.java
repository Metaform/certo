package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.MediaType;
import org.metaform.certo.common.OutboundJsonClient;
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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reports the consumer's Acceptance-phase outcome back to the Certificate Provider by POSTing a
 * {@code CertificateAcceptanceStatus} CloudEvent to {@code /certificate-acceptance-notifications}
 * (CX-0135 &sect;4.4.4), closing the exchange loop.
 *
 * <p>Best-effort (via {@link OutboundJsonClient}): a failure to deliver (transport error, or the provider not
 * recognizing the exchange) is logged but does not disrupt the consumer's own processing — the consumer has
 * already recorded its decision locally.
 */
@Component
public class Ccm300Reporter implements ProtocolAcceptanceReporter {

    private static final MediaType CLOUDEVENTS_JSON = MediaType.get(CcmEvents.CONTENT_TYPE);

    private final OutboundJsonClient outbound;
    private final OutboundTokens outboundTokens;
    private final Clock clock;

    public Ccm300Reporter(OutboundJsonClient outbound, OutboundTokens outboundTokens, Clock clock) {
        this.outbound = outbound;
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
                // Deterministic id per (exchange, verdict), so a re-report (e.g. reconciling a lost report) is
                // deduplicated by the provider on source+id rather than re-applied. RETRIEVED vs the terminal
                // verdict get distinct ids (both are legitimate events for the exchange).
                acceptanceEventId(exchangeId, status),
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                CcmEvents.SCHEMA_ACCEPTANCE_STATUS,
                call.sender().bpn(),
                data);

        var resolved = outboundTokens.forCall(call);
        outbound.postTo(resolved.baseUrl(), "certificate-acceptance-notifications", event, CLOUDEVENTS_JSON,
                resolved.bearer(), "report acceptance " + status + " for exchange " + exchangeId);
    }

    /** A stable CloudEvent id for an acceptance event, so a re-report of the same (exchange, verdict) dedups. */
    private static String acceptanceEventId(String exchangeId, AcceptanceStatus status) {
        return UUID.nameUUIDFromBytes((exchangeId + "|" + status).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
