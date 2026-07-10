package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.protocol.ccm240.Ccm240DocumentIds;
import org.metaform.certo.protocol.ccm240.Ccm240OutboundClient;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ProtocolAcceptanceReporter;
import org.metaform.certo.protocol.ProtocolVersions;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Error;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The CX-0135 <b>v2.4.0</b> consumer&rarr;provider acceptance reporter: renders the outcome as a legacy
 * {@code /companycertificate/status} message and POSTs it to the provider's feedback URL
 * ({@code binding.callbackUrl()}). {@code documentId} is the certificateId; the v3-only {@code ERRORED}
 * down-maps to {@code REJECTED} (its detail preserved in {@code certificateErrors}).
 */
@Component
public class Ccm240Reporter implements ProtocolAcceptanceReporter {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240Reporter.class);

    private final Ccm240OutboundClient outbound;
    private final Ccm240DocumentIds documentIds;
    private final CertoProperties properties;
    private final Clock clock;

    public Ccm240Reporter(Ccm240OutboundClient outbound, Ccm240DocumentIds documentIds,
                                    CertoProperties properties, Clock clock) {
        this.outbound = outbound;
        this.documentIds = documentIds;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String version() {
        return ProtocolVersions.CCM_2_4_0;
    }

    @Override
    public void report(ExchangeBinding binding, String exchangeId, String certificateId,
                       AcceptanceStatus status, List<StatusError> errors) {
        if (binding == null || binding.callbackUrl() == null) {
            LOG.warn("No v2.4.0 feedback URL for exchange {}; dropping acceptance {}", exchangeId, status);
            return;
        }
        var legacyStatus = Ccm240Translation.toCcm240StatusValue(status);
        var content = new Ccm240CertificateStatus.Content(
                documentIds.documentIdFor(certificateId), legacyStatus, toCcm240Errors(errors), null, null);
        var header = new Ccm240Header(Ccm240Contexts.STATUS, UUID.randomUUID().toString(),
                properties.consumer().bpn(), binding.peerBpn(), OffsetDateTime.now(clock).toString(),
                "3.1.0", binding.messageId(), null);
        var delivered = outbound.post(binding.callbackUrl(), new Ccm240CertificateStatus(header, content));
    }

    private static List<Ccm240Error> toCcm240Errors(List<StatusError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return errors.stream().map(e -> new Ccm240Error(e.message())).toList();
    }
}
