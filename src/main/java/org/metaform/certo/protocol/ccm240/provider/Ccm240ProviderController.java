package org.metaform.certo.protocol.ccm240.provider;

import org.metaform.certo.protocol.ccm240.Ccm240Envelope;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.SecurityTokenInterceptor;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateRequest;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Error;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestReply;
import org.metaform.certo.provider.ProviderExchangeService;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inbound adapter for the CX-0135 <b>v2.4.0</b> <em>provider-facing</em> endpoints, so a v2.4.0 consumer
 * can interact with this v3 provider. Each request is translated and delegated to
 * {@link ProviderExchangeService}; the interaction's protocol version (2.4.0) is recorded as an
 * {@link ExchangeBinding} so later outbound messages route back the same way.
 */
@RestController
public class Ccm240ProviderController {


    private final ProviderExchangeService provider;
    private final ExchangeBindingStore bindings;

    public Ccm240ProviderController(ProviderExchangeService provider, ExchangeBindingStore bindings) {
        this.provider = provider;
        this.bindings = bindings;
    }

    /** {@code POST /companycertificate/request} — request a certificate (consumer &rarr; provider). */
    @PostMapping(path = "/companycertificate/request",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Ccm240RequestReply> request(
            @RequestBody Ccm240CertificateRequest message,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.REQUEST);
        var content = message.content();
        if (content == null || content.certificateType() == null) {
            throw ApiException.badRequest("v2.4.0 request is missing content.certificateType");
        }
        Ccm240Envelope.requireBpnl("certifiedBpn", content.certifiedBpn());
        Ccm240Envelope.validateLocationBpns(content.locationBpns());
        var response = provider.requestCertificate(
                new CertificateRequest(content.certificateType(), content.locationBpns()), requestContext);

        var senderBpn = message.header() == null ? null : message.header().senderBpn();
        var messageId = message.header() == null ? null : message.header().messageId();
        // The counterparty is a v2.4.0 consumer; the outbound endpoint comes from the siglet cache. The binding
        // is keyed for inbound correlation on the consumer's VERIFIED DID (not the self-declared header BPN),
        // while peerBpn is kept only as the v2.4.0 wire receiver.
        bindings.record(new ExchangeBinding(response.exchangeId(), response.certificateId(),
                ProtocolVersion.CCM_2_4_0, CounterpartyRole.CONSUMER, senderBpn, requestContext.subject(), messageId));

        return switch (Ccm240Translation.toReplyStatus(response.status())) {
            case IN_PROGRESS -> ResponseEntity.accepted().body(Ccm240RequestReply.inProgress());
            case COMPLETED -> ResponseEntity.ok(Ccm240RequestReply.completed(response.certificateId()));
            case REJECTED -> ResponseEntity.ok(Ccm240RequestReply.rejected(toRejectionReplyErrors(response.errors())));
        };
    }

    /** {@code POST /companycertificate/status} — acceptance feedback on a certificate (consumer &rarr; provider). */
    @PostMapping(path = "/companycertificate/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> status(@RequestBody Ccm240CertificateStatus message,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.STATUS);
        var content = message.content();
        if (content == null || content.certificateStatus() == null) {
            throw ApiException.badRequest("v2.4.0 status is missing content.certificateStatus");
        }
        Ccm240Envelope.requireUuid("documentId", content.documentId());
        Ccm240Envelope.validateLocationBpns(content.locationBpns());
        // The v2.4.0 documentId is the certificateId (a UUID); no translation table.
        var certificateId = content.documentId();
        // Correlate on the caller's VERIFIED DID (not the self-declared header senderBpn), so a caller cannot
        // resolve another consumer's exchange.
        var exchangeId = bindings.exchangeFor(certificateId, requestContext.subject())
                .orElseThrow(() -> ApiException.notFound(
                        "No exchange for documentId " + content.documentId() + " from the calling consumer"));

        var status = Ccm240Translation.toAcceptanceStatus(content.certificateStatus());
        var errors = toStatusErrors(content, status);
        // v2.4.0 has no CloudEvent id; derive a dedup key from the messageId (scoped to the verified caller) so
        // a retransmission collapses. The sender is the authenticated caller, not the self-declared header BPN.
        var messageId = message.header().messageId() != null ? message.header().messageId() : UUID.randomUUID().toString();
        var dedupKey = "ccm240-status:" + requestContext.subject() + ":" + messageId;
        provider.recordAcceptance(new AcceptanceStatusData(exchangeId, certificateId, status, errors),
                dedupKey, requestContext);
        return ResponseEntity.ok().build();
    }

    // --- translation helpers -----------------------------------------------------------------------

    private static List<Ccm240Error> toRejectionReplyErrors(List<StatusError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of(new Ccm240Error("Request rejected"));
        }
        return errors.stream().map(e -> new Ccm240Error(e.message())).toList();
    }

    private static List<StatusError> toStatusErrors(Ccm240CertificateStatus.Content content, AcceptanceStatus status) {
        var errors = new ArrayList<StatusError>();
        if (content.certificateErrors() != null) {
            content.certificateErrors().forEach(e -> errors.add(new StatusError(e.message())));
        }
        if (content.locationErrors() != null) {
            for (var collection : content.locationErrors()) {
                if (collection.locationErrors() != null) {
                    collection.locationErrors().forEach(e -> errors.add(new StatusError(e.message(), collection.bpn())));
                }
            }
        }
        // v2.4.0 makes error detail optional even for a rejection; the v3 core requires a non-empty errors
        // array for REJECTED/ERRORED, so synthesize a default rather than bounce a valid legacy message.
        if (errors.isEmpty() && status.requiresErrors()) {
            errors.add(new StatusError("Certificate " + status.name().toLowerCase() + " by consumer"));
        }
        return errors.isEmpty() ? null : errors;
    }
}
