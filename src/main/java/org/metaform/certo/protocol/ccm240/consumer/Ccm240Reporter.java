package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Error;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;
import org.metaform.certo.protocol.ccm240.model.Ccm240LocationError;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolAcceptanceReporter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static java.util.UUID.randomUUID;

/**
 * The CX-0135 <b>v2.4.0</b> consumer&rarr;provider acceptance reporter: renders the outcome as a v2.4.0
 * {@code /companycertificate/status} message and POSTs it to the provider endpoint. The
 * wire {@code documentId} is the certificateId (a UUID); the v3-only {@code ERRORED} down-maps to
 * {@code REJECTED}. Error detail is split by scope: a {@link StatusError} with no {@code specifier} is a
 * certificate-level error ({@code certificateErrors}); one whose {@code specifier} is a site BPN is a
 * location-level error, grouped by that BPN into {@code locationErrors} — the inverse of how the inbound
 * {@code /companycertificate/status} handler maps them to {@link StatusError}s.
 */
@Component
public class Ccm240Reporter implements ProtocolAcceptanceReporter {


    private final OutboundJsonClient client;
    private final OutboundTokenCache outboundTokenCache;
    private final Clock clock;

    public Ccm240Reporter(OutboundJsonClient client, OutboundTokenCache outboundTokenCache, Clock clock) {
        this.client = client;
        this.outboundTokenCache = outboundTokenCache;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    @Override
    public boolean report(ExchangeBinding binding,
                          String exchangeId,
                          String certificateId,
                          AcceptanceStatus status,
                          List<StatusError> errors,
                          OutboundCall call) {
        // Token + provider endpoint from the cache (keyed by the flow).
        var resolved = outboundTokenCache.forCall(call);
        var v240Status = Ccm240Translation.toCcm240StatusValue(status);
        var content = new Ccm240CertificateStatus.Content(
                certificateId, v240Status, certificateErrors(errors), null, locationErrors(errors));
        var receiverBpn = binding != null && binding.peerBpn() != null ? binding.peerBpn() : call.counterpartyBpn();
        var header = new Ccm240Header(Ccm240Contexts.STATUS,
                randomUUID().toString(),
                call.sender().bpn(),
                receiverBpn,
                OffsetDateTime.now(clock).toString(),
                "3.1.0", binding == null ? null : binding.messageId(),
                null);
        return client.postToUrl(Ccm240OutboundClient.endpoint(resolved.baseUrl(), "status"),
                new Ccm240CertificateStatus(header, content), Ccm240OutboundClient.JSON, resolved.bearer(),
                "v2.4.0 status " + status + " for exchange " + exchangeId);
    }

    /** Certificate-scoped errors (no {@code specifier}) &rarr; {@code certificateErrors}; null if none. */
    private static List<Ccm240Error> certificateErrors(List<StatusError> errors) {
        if (errors == null) {
            return null;
        }
        var certificateErrors = errors.stream()
                .filter(error -> error.specifier() == null)
                .map(error -> new Ccm240Error(error.message()))
                .toList();
        return certificateErrors.isEmpty() ? null : certificateErrors;
    }

    /**
     * Location-scoped errors (a {@code specifier} = site BPN) grouped by that BPN &rarr; {@code locationErrors};
     * null if none. Preserves error order within each location.
     */
    private static List<Ccm240LocationError> locationErrors(List<StatusError> errors) {
        if (errors == null) {
            return null;
        }
        var byBpn = new LinkedHashMap<String, List<Ccm240Error>>();
        for (var error : errors) {
            if (error.specifier() != null) {
                byBpn.computeIfAbsent(error.specifier(), _ -> new ArrayList<>()).add(new Ccm240Error(error.message()));
            }
        }
        if (byBpn.isEmpty()) {
            return null;
        }
        return byBpn.entrySet().stream()
                .map(entry -> new Ccm240LocationError(entry.getKey(), entry.getValue()))
                .toList();
    }
}
