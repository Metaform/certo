package org.metaform.certo.protocol.ccm240.consumer;

import org.metaform.certo.protocol.ccm240.Ccm240Envelope;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.cloudevent.store.ProcessedEventStore;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.common.security.inbound.SecurityTokenInterceptor;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.ConsumerCatalogService;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateAvailable;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificatePush;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

    private final ConsumerExchangeService exchangeService;
    private final ConsumerCatalogService catalogService;
    private final ExchangeBindingStore bindingStore;
    private final ProcessedEventStore eventStore;

    public Ccm240ConsumerController(ConsumerExchangeService exchangeService,
                                    ConsumerCatalogService catalogService,
                                    ExchangeBindingStore bindingStore,
                                    ProcessedEventStore eventStore) {
        this.exchangeService = exchangeService;
        this.catalogService = catalogService;
        this.bindingStore = bindingStore;
        this.eventStore = eventStore;
    }

    /** A small ack body carrying the assigned v3 identifiers (a convenience; v2.4.0 clients ignore it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ccm240PushAck(String certificateId, String exchangeId) {
    }

    /** Ack for an {@code /available} notice: the assigned identifiers a client uses to drive the pull. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ccm240AvailableAck(String certificateId, String exchangeId) {
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

        // Idempotency: v2.4.0 has no CloudEvent id, but a retransmission repeats header.messageId. Claim it
        // (scoped to the verified caller) inside this transaction so a duplicate push does not bump the
        // revision or open a second exchange (adapter-architecture §7.4). A duplicate returns the exchange the
        // first delivery opened.
        var dedupKey = "ccm240-push:" + requestContext.subject() + ":" + header.messageId();
        if (!eventStore.claim(dedupKey)) {
            var priorExchangeId = bindingStore.exchangeFor(certificateId, requestContext.subject()).orElse(null);
            return ResponseEntity.ok(new Ccm240PushAck(certificateId, priorExchangeId));
        }

        var revision = catalogService.nextPushedRevision(requestContext.participantContextId(), certificateId);
        var certificate = Ccm240Translation.upConvert(content, certificateId, revision);

        // A v2.4.0 provider assigns no exchangeId; mint a consumer-local surrogate per delivery.
        var exchangeId = UUID.randomUUID().toString();
        bindingStore.record(new ExchangeBinding(exchangeId, certificateId, ProtocolVersion.CCM_2_4_0,
                CounterpartyRole.PROVIDER, header.senderBpn(), requestContext.subject(), header.messageId()));

        // Hand the embedded certificate to our consumer as a CREATED; it accepts inline and reports the
        // outcome back to this v2.4.0 provider (routed by the binding above). No provider role is played.
        exchangeService.receivePushedCertificate(exchangeId, certificate, requestContext);

        return ResponseEntity.ok(new Ccm240PushAck(certificateId, exchangeId));
    }

    /**
     * {@code POST /companycertificate/available} — availability notice, <b>by reference</b> (provider &rarr;
     * consumer). Opens a by-reference Certificate Exchange keyed to the notice's {@code documentId} (which is
     * the certificateId, a UUID) and emits it, so a client drives {@code retrieve} — a v2.4.0 asset pull of the
     * content over a flow — then {@code accept}. No content is embedded, so the exchange holds only what the
     * notice stated (id, type, locations) until the pull fills in the full record.
     *
     * <p>Because v2.4.0 assigns no {@code exchangeId}, the adapter mints a consumer-local surrogate per notice
     * and records the provider's protocol (2.4.0) + verified DID against it, so the later pull routes to the
     * v2.4.0 retriever and the acceptance reports back as a v2.4.0 {@code /status}. Idempotent on
     * {@code header.messageId}: a re-notice returns the exchange the first opened.
     */
    @PostMapping(path = "/companycertificate/available",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Ccm240AvailableAck> available(@RequestBody Ccm240CertificateAvailable message,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.AVAILABLE);
        var content = message.content();
        if (content == null || content.documentId() == null) {
            throw ApiException.badRequest("v2.4.0 available is missing content.documentId");
        }
        // The v2.4.0 documentId is the certificateId (a UUID); it doubles as the /status documentId later.
        Ccm240Envelope.requireUuid("documentId", content.documentId());
        Ccm240Envelope.validateLocationBpns(content.locationBpns());
        var header = message.header();
        var certificateId = content.documentId();

        // Idempotency: a retransmission repeats header.messageId. Claim it (scoped to the verified caller) so a
        // duplicate notice does not open a second exchange; a duplicate returns the exchange the first opened.
        var dedupKey = "ccm240-available:" + requestContext.subject() + ":" + header.messageId();
        if (!eventStore.claim(dedupKey)) {
            var priorExchangeId = bindingStore.exchangeFor(certificateId, requestContext.subject()).orElse(null);
            return ResponseEntity.ok(new Ccm240AvailableAck(certificateId, priorExchangeId));
        }

        // A v2.4.0 provider assigns no exchangeId; mint a consumer-local surrogate per notice.
        var exchangeId = UUID.randomUUID().toString();
        bindingStore.record(new ExchangeBinding(exchangeId, certificateId, ProtocolVersion.CCM_2_4_0,
                CounterpartyRole.PROVIDER, header.senderBpn(), requestContext.subject(), header.messageId()));

        exchangeService.receiveAvailableCertificate(exchangeId, byReferenceRecord(certificateId, content), requestContext);

        return ResponseEntity.ok(new Ccm240AvailableAck(certificateId, exchangeId));
    }

    /**
     * Builds the by-reference certificate stub an {@code /available} notice carries: the id plus whatever the
     * notice stated (type, locations). Dates, issuer, documents and the rest are absent until the pull.
     */
    private static CertificateRecord byReferenceRecord(String certificateId, Ccm240CertificateAvailable.Content content) {
        List<CertifiedLocation> locations = null;
        if (content.locationBpns() != null && !content.locationBpns().isEmpty()) {
            locations = content.locationBpns().stream()
                    .map(bpn -> new CertifiedLocation(
                            null,
                            bpn != null && bpn.startsWith("BPNA") ? bpn : null,
                            bpn != null && bpn.startsWith("BPNS") ? bpn : null,
                            LocationRole.ENCLOSED_LOCATION))
                    .toList();
        }
        return new CertificateRecord(certificateId, 1, content.certificateType(), null, null,
                null, null, null, null, locations, null, null, null);
    }
}
