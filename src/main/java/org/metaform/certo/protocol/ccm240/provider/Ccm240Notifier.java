package org.metaform.certo.protocol.ccm240.provider;

import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;

import org.metaform.certo.common.OutboundJsonClient;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolNotifier;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.provider.spi.HeldCertificateLookup;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificatePush;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The CX-0135 <b>v2.4.0</b> provider&rarr;consumer notifier: renders a {@code CREATED} lifecycle event as a
 * v2.4.0 message and POSTs it to the consumer endpoint from the siglet cache. An embedded push
 * (full certificate inline) is delivered as {@code /companycertificate/push} — which the consumer ingests
 * directly; a by-reference push is delivered as {@code /companycertificate/available} (acknowledged only).
 * A v2.4.0 consumer has no fulfillment-status endpoint, so an asynchronous {@code FULFILLED} is surfaced as
 * an {@code /available} notice (the consumer then pulls) and other fulfillment statuses are suppressed;
 * {@code WITHDRAWN} has no v2.4.0 equivalent and is suppressed too.
 */
@Component
public class Ccm240Notifier implements ProtocolNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240Notifier.class);

    private final OutboundJsonClient outbound;
    private final OutboundTokens outboundTokens;
    private final HeldCertificateLookup certificates;
    private final Clock clock;

    public Ccm240Notifier(OutboundJsonClient outbound, OutboundTokens outboundTokens,
                          HeldCertificateLookup certificates, Clock clock) {
        this.outbound = outbound;
        this.outboundTokens = outboundTokens;
        this.certificates = certificates;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    @Override
    public boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data, OutboundCall call) {
        // Token + counterparty endpoint from the siglet cache (keyed by the flow).
        var resolved = outboundTokens.forCall(call);
        var base = resolved.baseUrl();
        var bearer = resolved.bearer();
        var peerBpn = call.counterpartyBpn();
        if (data.status() == LifecycleStatus.WITHDRAWN) {
            LOG.info("Suppressing WITHDRAWN to v2.4.0 consumer {} (no v2.4.0 equivalent)", peerBpn);
            return true;
        }
        var cert = data.certificate();
        if (cert.hasEmbeddedContent()) {
            // Full certificate inline -> /companycertificate/push; the consumer ingests it directly.
            var message = new Ccm240CertificatePush(
                    header(Ccm240Contexts.PUSH, call.sender().bpn(), peerBpn), Ccm240Translation.downConvert(cert));
            return outbound.postToUrl(Ccm240OutboundClient.endpoint(base, "push"), message, Ccm240OutboundClient.JSON,
                    bearer, "v2.4.0 push certificate " + cert.certificateId() + " to " + peerBpn);
        }
        // Reference only -> /companycertificate/available (acknowledged; a real consumer then pulls via EDC).
        var content = new Ccm240CertificateAvailable.Content(
                cert.certificateId(), cert.certificateType(), locationBpns(cert.certifiedLocations()));
        var message = new Ccm240CertificateAvailable(
                header(Ccm240Contexts.AVAILABLE, call.sender().bpn(), peerBpn), content);
        return outbound.postToUrl(Ccm240OutboundClient.endpoint(base, "available"), message, Ccm240OutboundClient.JSON,
                bearer, "v2.4.0 available certificate " + cert.certificateId() + " to " + peerBpn);
    }

    /**
     * v2.4.0 has no fulfillment-status endpoint. A terminal {@code FULFILLED} — the certificate became
     * available asynchronously (e.g. a consumer-initiated request the backend issued later) — is surfaced to
     * the v2.4.0 consumer as a {@code /companycertificate/available} notice so it learns to pull, per
     * adapter-architecture §5.3. Non-{@code FULFILLED} statuses (in-progress, or the DECLINED/FAILED
     * dead-ends) have no v2.4.0 equivalent and are suppressed.
     */
    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data, OutboundCall call) {
        if (data.status() != FulfillmentStatus.FULFILLED) {
            LOG.info("Suppressing fulfillment status {} to v2.4.0 consumer {} (no v2.4.0 equivalent)",
                    data.status(), call.counterpartyBpn());
            return true;
        }
        var resolved = outboundTokens.forCall(call);
        // By-reference availability: the v2.4.0 documentId is the certificateId (a UUID); the consumer pulls
        // the content and then reports acceptance via /companycertificate/status. Enrich with the certificate's
        // type + locations (the fulfillment event carries only the id) so the notice is self-describing.
        var cert = certificates.find(call.sender().participantContextId(), data.certificateId()).orElse(null);
        var content = new Ccm240CertificateAvailable.Content(data.certificateId(),
                cert == null ? null : cert.certificateType(),
                cert == null ? null : locationBpns(cert.certifiedLocations()));
        var message = new Ccm240CertificateAvailable(
                header(Ccm240Contexts.AVAILABLE, call.sender().bpn(), call.counterpartyBpn()), content);
        return outbound.postToUrl(Ccm240OutboundClient.endpoint(resolved.baseUrl(), "available"), message,
                Ccm240OutboundClient.JSON, resolved.bearer(),
                "v2.4.0 available (fulfilled) certificate " + data.certificateId() + " to " + call.counterpartyBpn());
    }

    private Ccm240Header header(String context, String senderBpn, String receiverBpn) {
        return new Ccm240Header(context, UUID.randomUUID().toString(), senderBpn,
                receiverBpn, OffsetDateTime.now(clock).toString(), "3.1.0", null, null);
    }

    private static List<String> locationBpns(List<CertifiedLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        var bpns = new ArrayList<String>();
        for (var location : locations) {
            if (location.bpns() != null) {
                bpns.add(location.bpns());
            } else if (location.bpna() != null) {
                bpns.add(location.bpna());
            }
        }
        return bpns.isEmpty() ? null : bpns;
    }
}
