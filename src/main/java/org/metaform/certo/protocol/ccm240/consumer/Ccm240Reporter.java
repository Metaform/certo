package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import org.metaform.certo.common.OutboundJsonClient;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolAcceptanceReporter;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Error;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The CX-0135 <b>v2.4.0</b> consumer&rarr;provider acceptance reporter: renders the outcome as a v2.4.0
 * {@code /companycertificate/status} message and POSTs it to the provider endpoint from the siglet cache. The
 * wire {@code documentId} is the certificateId (a UUID); the v3-only {@code ERRORED} down-maps to
 * {@code REJECTED} (its detail preserved in {@code certificateErrors}).
 */
@Component
public class Ccm240Reporter implements ProtocolAcceptanceReporter {


    private final OutboundJsonClient outbound;
    private final OutboundTokens outboundTokens;
    private final Clock clock;

    public Ccm240Reporter(OutboundJsonClient outbound, OutboundTokens outboundTokens, Clock clock) {
        this.outbound = outbound;
        this.outboundTokens = outboundTokens;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    @Override
    public void report(ExchangeBinding binding, String exchangeId, String certificateId,
                       AcceptanceStatus status, List<StatusError> errors, OutboundCall call) {
        // Token + provider endpoint from the siglet cache (keyed by the flow).
        var resolved = outboundTokens.forCall(call);
        var v240Status = Ccm240Translation.toCcm240StatusValue(status);
        var content = new Ccm240CertificateStatus.Content(
                certificateId, v240Status, toReportedErrors(errors), null, null);
        var receiverBpn = binding != null && binding.peerBpn() != null ? binding.peerBpn() : call.counterpartyBpn();
        var header = new Ccm240Header(Ccm240Contexts.STATUS, UUID.randomUUID().toString(),
                call.sender().bpn(), receiverBpn,
                OffsetDateTime.now(clock).toString(), "3.1.0", binding == null ? null : binding.messageId(), null);
        outbound.postToUrl(Ccm240OutboundClient.endpoint(resolved.baseUrl(), "status"),
                new Ccm240CertificateStatus(header, content), Ccm240OutboundClient.JSON, resolved.bearer(),
                "v2.4.0 status " + status + " for exchange " + exchangeId);
    }

    private static List<Ccm240Error> toReportedErrors(List<StatusError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return errors.stream().map(e -> new Ccm240Error(e.message())).toList();
    }
}
