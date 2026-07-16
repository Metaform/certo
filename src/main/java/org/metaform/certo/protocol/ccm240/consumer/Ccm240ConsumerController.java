package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.protocol.ccm240.Ccm240Envelope;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.security.SecurityTokenInterceptor;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.ConsumerCatalogService;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificatePush;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
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

    private final ConsumerExchangeService consumer;
    private final ConsumerCatalogService catalog;
    private final ExchangeBindingStore bindings;

    public Ccm240ConsumerController(ConsumerExchangeService consumer, ConsumerCatalogService catalog,
                                    ExchangeBindingStore bindings) {
        this.consumer = consumer;
        this.catalog = catalog;
        this.bindings = bindings;
    }

    /** A small ack body carrying the assigned v3 identifiers (a convenience; v2.4.0 clients ignore it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ccm240PushAck(String certificateId, String exchangeId) {
    }

    /**
     * {@code POST /companycertificate/push} — full certificate pushed inline (provider &rarr; consumer).
     *
     * <p><b>Certificate identity.</b> A v2.4.0 push carries no v3 {@code certificateId} — the
     * {@code BusinessPartnerCertificate} has only business identifiers (issuer, registration number) and an
     * inline {@code document} whose {@code documentID} is the <em>binary's</em> id, not the certificate's. So
     * the adapter <b>derives</b> a stable {@code certificateId}: a deterministic name-based UUID of
     * {@code issuerBpn|registrationNumber} (falling back to the transmitting {@code senderBpn} when the issuer
     * is absent). This gives three properties at once:
     * <ul>
     *   <li><b>Continuity</b> — the same certificate re-pushed yields the <em>same</em> id, so it maps to the
     *       consumer's existing known certificate instead of creating a duplicate each time;</li>
     *   <li><b>A valid v2.4.0 asset id</b> — the id is a UUID, so it doubles as the {@code documentId} the
     *       consumer sends on the acceptance {@code /companycertificate/status} back to the provider (which
     *       validates it as a UUID);</li>
     *   <li><b>Revisioning</b> — an <em>updated</em> push (same identity, newer content) is recorded as the
     *       next {@link ConsumerCatalogService#nextPushedRevision revision} of that known certificate
     *       rather than a fresh record.</li>
     * </ul>
     * The exchange, by contrast, is per-delivery: because v2.4.0 has no exchange concept the adapter mints a
     * fresh consumer-local surrogate {@code exchangeId} per push and records the provider's protocol (2.4.0)
     * against it, so the resulting acceptance is reported back as a v2.4.0 {@code /status}.
     */
    @PostMapping(path = "/companycertificate/push",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Ccm240PushAck> push(@RequestBody Ccm240CertificatePush message,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.PUSH);
        var content = message.content();
        if (content == null) {
            throw ApiException.badRequest("v2.4.0 push is missing the certificate content");
        }
        ApiException.requireText(content.registrationNumber(),
                "v2.4.0 push is missing content.registrationNumber (needed for a stable certificate id)");
        var header = message.header();
        // Derive a stable UUID certificateId from the certificate's identity (issuer + registration number),
        // so re-pushes map to the same certificate and its revisions accrue (see the method javadoc).
        var issuerBpn = content.issuer() != null ? content.issuer().issuerBpn() : null;
        var identityKey = (issuerBpn != null ? issuerBpn : header.senderBpn()) + "|" + content.registrationNumber();
        var certificateId = UUID.nameUUIDFromBytes(identityKey.getBytes(StandardCharsets.UTF_8)).toString();
        var revision = catalog.nextPushedRevision(requestContext.participantContextId(), certificateId);
        var certificate = Ccm240Translation.upConvert(content, certificateId, revision);

        // A v2.4.0 provider assigns no exchangeId; mint a consumer-local surrogate per delivery.
        var exchangeId = UUID.randomUUID().toString();
        bindings.record(new ExchangeBinding(exchangeId, certificateId, ProtocolVersion.CCM_2_4_0,
                CounterpartyRole.PROVIDER, header.senderBpn(), header.messageId()));

        // Hand the embedded certificate to our consumer as a CREATED; it accepts inline and reports the
        // outcome back to this v2.4.0 provider (routed by the binding above). No provider role is played.
        consumer.receivePushedCertificate(exchangeId, certificate, requestContext);

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
        LOG.info("v2.4.0 'available' for documentId {} acknowledged; this adapter does not perform the v2.4.0 "
                + "asset-pull for the content (old providers should use /companycertificate/push)", documentId);
        return ResponseEntity.ok().build();
    }
}
