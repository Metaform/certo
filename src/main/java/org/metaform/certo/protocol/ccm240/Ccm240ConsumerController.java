package org.metaform.certo.protocol.ccm240;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersions;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificatePush;
import org.metaform.certo.provider.ProviderCertificateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Inbound adapter for the CX-0135 <b>v2.4.0</b> <em>consumer-facing</em> endpoints, so a v2.4.0 provider
 * can deliver to this v3 participant.
 *
 * <ul>
 *   <li>{@code POST /companycertificate/push} — the full 3.1.0 certificate is up-converted, ingested into
 *       the provider data plane, and published, driving the normal v3 consumer pull/evaluate/accept loop.
 *       The provider's protocol (2.4.0) + feedback URL is recorded so the resulting acceptance is reported
 *       back to it as a v2.4.0 {@code /status}.</li>
 *   <li>{@code POST /companycertificate/available} — acknowledged only; retrieving the content required the
 *       legacy per-asset EDC pull, which is out of scope (old providers should use {@code /push}).</li>
 * </ul>
 */
@RestController
public class Ccm240ConsumerController {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240ConsumerController.class);

    private final ProviderCertificateService provider;
    private final ExchangeBindingStore bindings;

    public Ccm240ConsumerController(ProviderCertificateService provider, ExchangeBindingStore bindings) {
        this.provider = provider;
        this.bindings = bindings;
    }

    /** A small ack body carrying the assigned v3 identifiers (a demo convenience; legacy clients ignore it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ccm240PushAck(String certificateId, String exchangeId) {
    }

    /** {@code POST /companycertificate/push} — full certificate pushed inline (provider &rarr; consumer). */
    @PostMapping(path = "/companycertificate/push",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Ccm240PushAck> push(@RequestBody Ccm240CertificatePush message) {
        if (message.content() == null) {
            throw ApiException.badRequest("v2.4.0 push is missing the certificate content");
        }
        var certificateId = "cert-legacy-" + UUID.randomUUID();
        var upConverted = Ccm240Translation.upConvert(message.content(), certificateId, 1);
        provider.ingestExternalCertificate(upConverted.record(), upConverted.document());

        var header = message.header();
        var peerBpn = header == null ? null : header.senderBpn();
        var messageId = header == null ? null : header.messageId();
        var feedbackUrl = header == null ? null : header.senderFeedbackUrl();

        // Record the binding *before* publishing: the consumer's acceptance report fires synchronously inside
        // publish(), before the exchangeId is known, and must route back to this v2.4.0 provider.
        bindings.record(new ExchangeBinding(null, certificateId, ProtocolVersions.CCM_2_4_0,
                CounterpartyRole.PROVIDER, peerBpn, messageId, feedbackUrl));

        var publication = provider.publish(certificateId, null);
        bindings.record(new ExchangeBinding(publication.exchangeId(), certificateId, ProtocolVersions.CCM_2_4_0,
                CounterpartyRole.PROVIDER, peerBpn, messageId, feedbackUrl));

        LOG.info("Ingested v2.4.0 push -> certificate {} (exchange {})", certificateId, publication.exchangeId());
        return ResponseEntity.ok(new Ccm240PushAck(certificateId, publication.exchangeId()));
    }

    /** {@code POST /companycertificate/available} — availability notice by reference only. */
    @PostMapping(path = "/companycertificate/available", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> available(@RequestBody Ccm240CertificateAvailable message) {
        var documentId = message.content() == null ? null : message.content().documentId();
        LOG.info("v2.4.0 'available' for documentId {} acknowledged; content retrieval via legacy asset-pull "
                + "is out of scope (old providers should use /companycertificate/push)", documentId);
        return ResponseEntity.ok().build();
    }
}
