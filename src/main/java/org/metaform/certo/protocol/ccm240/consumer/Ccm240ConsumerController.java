package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.protocol.ccm240.Ccm240Envelope;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.ConsumerCertificateService;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersions;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificatePush;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
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
 *   <li>{@code POST /companycertificate/push} — the full 3.1.0 certificate is up-converted to a complete
 *       (embedded-document) v3 record and handed to the consumer as a {@code CREATED}, which it accepts
 *       inline without a pull. Because a v2.4.0 provider assigns no {@code exchangeId} (2.4.0 has no
 *       exchange concept), the adapter mints a <em>consumer-local surrogate</em> up front and records the
 *       provider's protocol (2.4.0) + feedback URL against it, so the resulting acceptance is reported
 *       back as a v2.4.0 {@code /status}.</li>
 *   <li>{@code POST /companycertificate/available} — acknowledged only (old providers should use
 *       {@code /push}).</li>
 * </ul>
 */
@RestController
public class Ccm240ConsumerController {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240ConsumerController.class);

    private final ConsumerCertificateService consumer;
    private final ExchangeBindingStore bindings;

    public Ccm240ConsumerController(ConsumerCertificateService consumer, ExchangeBindingStore bindings) {
        this.consumer = consumer;
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
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.PUSH);
        if (message.content() == null) {
            throw ApiException.badRequest("v2.4.0 push is missing the certificate content");
        }
        var certificateId = "cert-legacy-" + UUID.randomUUID();
        // A v2.4.0 provider assigns no exchangeId; mint a consumer-local surrogate so we can drive and
        // correlate the exchange internally.
        var exchangeId = "exch-legacy-" + UUID.randomUUID();
        var certificate = Ccm240Translation.upConvert(message.content(), certificateId, 1);

        var header = message.header();
        bindings.record(new ExchangeBinding(exchangeId, certificateId, ProtocolVersions.CCM_2_4_0,
                CounterpartyRole.PROVIDER,
                header == null ? null : header.senderBpn(),
                header == null ? null : header.messageId(),
                header == null ? null : header.senderFeedbackUrl()));

        // Hand the embedded certificate to our consumer as a CREATED; it accepts inline and reports the
        // outcome back to this v2.4.0 provider (routed by the binding above). No provider role is played.
        consumer.receivePushedCertificate(exchangeId, certificate);

        return ResponseEntity.ok(new Ccm240PushAck(certificateId, exchangeId));
    }

    /** {@code POST /companycertificate/available} — availability notice by reference only. */
    @PostMapping(path = "/companycertificate/available", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> available(@RequestBody Ccm240CertificateAvailable message) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.AVAILABLE);
        if (message.content() != null) {
            Ccm240Envelope.requireUuid("documentId", message.content().documentId());
            Ccm240Envelope.validateLocationBpns(message.content().locationBpns());
        }
        var documentId = message.content() == null ? null : message.content().documentId();
        LOG.info("v2.4.0 'available' for documentId {} acknowledged; this adapter does not perform the legacy "
                + "asset-pull for the content (old providers should use /companycertificate/push)", documentId);
        return ResponseEntity.ok().build();
    }
}
