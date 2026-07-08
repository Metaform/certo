package org.metaform.certo.protocol.ccm240;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolNotifier;
import org.metaform.certo.protocol.ProtocolVersions;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
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
 * The CX-0135 <b>v2.4.0</b> provider&rarr;consumer notifier: renders a lifecycle event as a legacy
 * {@code /companycertificate/available} message and POSTs it to the consumer's endpoint
 * ({@code binding.callbackUrl()}). A legacy consumer has no fulfillment-notification endpoint, so
 * fulfillment pushes are suppressed; {@code WITHDRAWN} has no legacy equivalent and is suppressed too.
 */
@Component
public class Ccm240ConsumerNotifier implements ProtocolNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240ConsumerNotifier.class);

    private final Ccm240OutboundClient outbound;
    private final CertoProperties properties;
    private final Clock clock;

    public Ccm240ConsumerNotifier(Ccm240OutboundClient outbound, CertoProperties properties, Clock clock) {
        this.outbound = outbound;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String version() {
        return ProtocolVersions.CCM_2_4_0;
    }

    @Override
    public boolean notifyLifecycle(ExchangeBinding binding, LifecycleStatusData data) {
        if (binding == null || binding.callbackUrl() == null) {
            LOG.warn("No v2.4.0 callback URL for the consumer; cannot deliver {}", data.status());
            return false;
        }
        if (data.status() == LifecycleStatus.WITHDRAWN) {
            LOG.info("Suppressing WITHDRAWN to v2.4.0 consumer {} (no legacy equivalent)", binding.peerBpn());
            return true;
        }
        var cert = data.certificate();
        var content = new Ccm240CertificateAvailable.Content(
                cert.certificateId(), cert.certificateType(), locationBpns(cert.certifiedLocations()));
        var message = new Ccm240CertificateAvailable(header(binding.peerBpn()), content);
        var delivered = outbound.post(binding.callbackUrl(), message);
        LOG.info("Sent v2.4.0 'available' for {} to {} (delivered: {})", cert.certificateId(), binding.peerBpn(), delivered);
        return delivered;
    }

    @Override
    public boolean notifyFulfillment(ExchangeBinding binding, FulfillmentStatusData data) {
        var peer = binding == null ? null : binding.peerBpn();
        LOG.info("Suppressing fulfillment status {} to v2.4.0 consumer {} (no legacy fulfillment endpoint)",
                data.status(), peer);
        return true;
    }

    private Ccm240Header header(String receiverBpn) {
        return new Ccm240Header(Ccm240Contexts.AVAILABLE, UUID.randomUUID().toString(), properties.provider().bpn(),
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
