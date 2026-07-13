package org.metaform.certo.protocol.ccm240.provider;

import org.metaform.certo.protocol.ccm240.Ccm240DocumentIds;
import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolNotifier;
import org.metaform.certo.protocol.ProtocolVersion;
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
 * v2.4.0 message and POSTs it to the consumer's base URL ({@code binding.callbackUrl()}). An embedded push
 * (full certificate inline) is delivered as {@code /companycertificate/push} — which the consumer ingests
 * directly; a by-reference push is delivered as {@code /companycertificate/available} (acknowledged only).
 * A v2.4.0 consumer has no fulfillment-notification endpoint, so fulfillment pushes are suppressed;
 * {@code WITHDRAWN} has no v2.4.0 equivalent and is suppressed too.
 */
@Component
public class Ccm240Notifier implements ProtocolNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240Notifier.class);

    private final Ccm240OutboundClient outbound;
    private final Ccm240DocumentIds documentIds;
    private final CertoProperties properties;
    private final Clock clock;

    public Ccm240Notifier(Ccm240OutboundClient outbound, Ccm240DocumentIds documentIds,
                                  CertoProperties properties, Clock clock) {
        this.outbound = outbound;
        this.documentIds = documentIds;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    @Override
    public boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data) {
        if (binding == null || binding.callbackUrl() == null) {
            LOG.warn("No v2.4.0 base URL for the consumer; cannot deliver {}", data.status());
            return false;
        }
        if (data.status() == LifecycleStatus.WITHDRAWN) {
            LOG.info("Suppressing WITHDRAWN to v2.4.0 consumer {} (no v2.4.0 equivalent)", binding.peerBpn());
            return true;
        }
        var cert = data.certificate();
        if (hasEmbeddedContent(cert)) {
            // Full certificate inline -> /companycertificate/push; the consumer ingests it directly.
            var message = new Ccm240CertificatePush(
                    header(Ccm240Contexts.PUSH, binding.peerBpn()), Ccm240Translation.downConvert(cert));
            return outbound.post(Ccm240OutboundClient.endpoint(binding.callbackUrl(), "push"), message);
        }
        // Reference only -> /companycertificate/available (acknowledged; a real consumer then pulls via EDC).
        var content = new Ccm240CertificateAvailable.Content(
                documentIds.documentIdFor(cert.certificateId()), cert.certificateType(), locationBpns(cert.certifiedLocations()));
        var message = new Ccm240CertificateAvailable(header(Ccm240Contexts.AVAILABLE, binding.peerBpn()), content);
        return outbound.post(Ccm240OutboundClient.endpoint(binding.callbackUrl(), "available"), message);
    }

    private static boolean hasEmbeddedContent(CertificateRecord cert) {
        return cert.documents() != null && cert.documents().stream().anyMatch(d -> d.contentBase64() != null);
    }

    /**
     * v2.4.0 has no fulfillment-status endpoint, so fulfillment pushes to a v2.4.0 consumer are suppressed.
     */
    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data) {
        LOG.info("Suppressing fulfillment status {} to v2.4.0 consumer {} (no v2.4.0 fulfillment endpoint)",
                data.status(), binding == null ? null : binding.peerBpn());
        return true;
    }

    private Ccm240Header header(String context, String receiverBpn) {
        return new Ccm240Header(context, UUID.randomUUID().toString(), properties.provider().bpn(),
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
