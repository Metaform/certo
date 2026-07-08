package org.metaform.certo.protocol.ccm240;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersions;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateRequest;
import org.metaform.certo.protocol.ccm240.model.Ccm240CertificateStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240Contexts;
import org.metaform.certo.protocol.ccm240.model.Ccm240Error;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestReply;
import org.metaform.certo.provider.ProviderCertificateService;
import org.metaform.certo.provider.api.dto.CertificateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inbound adapter for the CX-0135 <b>v2.4.0</b> <em>provider-facing</em> endpoints, so a v2.4.0 consumer
 * can interact with this v3 provider. Each request is translated and delegated to
 * {@link ProviderCertificateService}; the interaction's protocol version (2.4.0) is recorded as an
 * {@link ExchangeBinding} so later outbound messages route back the same way.
 */
@RestController
public class Ccm240ProviderController {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm240ProviderController.class);

    private final ProviderCertificateService provider;
    private final ExchangeBindingStore bindings;
    private final Ccm240DocumentIds documentIds;
    private final CertoProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Ccm240ProviderController(ProviderCertificateService provider, ExchangeBindingStore bindings,
                                    Ccm240DocumentIds documentIds, CertoProperties properties,
                                    ObjectMapper mapper, Clock clock) {
        this.provider = provider;
        this.bindings = bindings;
        this.documentIds = documentIds;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** {@code POST /companycertificate/request} — request a certificate (consumer &rarr; provider). */
    @PostMapping(path = "/companycertificate/request",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Ccm240RequestReply> request(@RequestBody Ccm240CertificateRequest message) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.REQUEST);
        var content = message.content();
        if (content == null || content.certificateType() == null) {
            throw ApiException.badRequest("v2.4.0 request is missing content.certificateType");
        }
        Ccm240Envelope.requireBpnl("certifiedBpn", content.certifiedBpn());
        Ccm240Envelope.validateLocationBpns(content.locationBpns());
        var response = provider.requestCertificate(
                new CertificateRequest(content.certificateType(), content.locationBpns()));

        var senderBpn = message.header() == null ? null : message.header().senderBpn();
        var messageId = message.header() == null ? null : message.header().messageId();
        // The counterparty is a v2.4.0 consumer. The v2.4.0 request carries no callback URL, so the binding
        // records none; the consumer retrieves the certificate by documentId via the dataspace (out of scope).
        bindings.record(new ExchangeBinding(response.exchangeId(), response.certificateId(),
                ProtocolVersions.CCM_2_4_0, CounterpartyRole.CONSUMER, senderBpn, messageId, null));

        return switch (Ccm240Translation.toReplyStatus(response.status())) {
            case IN_PROGRESS -> ResponseEntity.accepted().body(Ccm240RequestReply.inProgress());
            case COMPLETED -> ResponseEntity.ok(Ccm240RequestReply.completed(documentIds.documentIdFor(response.certificateId())));
            case REJECTED -> ResponseEntity.ok(Ccm240RequestReply.rejected(toCcm240Errors(response.errors())));
        };
    }

    /** {@code POST /companycertificate/status} — acceptance feedback on a certificate (consumer &rarr; provider). */
    @PostMapping(path = "/companycertificate/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> status(@RequestBody Ccm240CertificateStatus message) {
        Ccm240Envelope.validate(message.header(), Ccm240Contexts.STATUS);
        var content = message.content();
        if (content == null || content.certificateStatus() == null) {
            throw ApiException.badRequest("v2.4.0 status is missing content.certificateStatus");
        }
        Ccm240Envelope.requireUuid("documentId", content.documentId());
        Ccm240Envelope.validateLocationBpns(content.locationBpns());
        var senderBpn = message.header() == null ? null : message.header().senderBpn();
        var certificateId = documentIds.certificateIdFor(content.documentId())
                .orElseThrow(() -> ApiException.notFound("Unknown documentId: " + content.documentId()));
        var exchangeId = bindings.exchangeFor(certificateId, senderBpn)
                .orElseThrow(() -> ApiException.notFound(
                        "No exchange for documentId " + content.documentId() + " from " + senderBpn));

        var status = Ccm240Translation.toAcceptanceStatus(content.certificateStatus());
        var errors = toStatusErrors(content);
        provider.recordAcceptance(acceptanceEvent(exchangeId, certificateId, status, errors, message));
        LOG.info("Translated v2.4.0 status {} (documentId {}) -> acceptance {} for exchange {}",
                content.certificateStatus(), content.documentId(), status, exchangeId);
        return ResponseEntity.ok().build();
    }

    /**
     * Demo trigger for a provider-initiated v2.4.0 push. There is no inbound request to derive the
     * counterparty's protocol from, so the caller specifies it: the target consumer's BPN and legacy
     * endpoint. The binding is recorded (keyed by certificateId, since the exchangeId is assigned inside
     * {@code publish}) so the notification routes to that v2.4.0 consumer.
     */
    @PostMapping(path = "/legacy/certificates/{id}/publish",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Ccm240PublishResult> publishToLegacyConsumer(@PathVariable("id") String certificateId,
                                                                       @RequestBody Ccm240PublishTarget target) {
        if (target == null || target.consumerUrl() == null) {
            throw ApiException.badRequest("A v2.4.0 publish must specify the consumer's endpoint (consumerUrl)");
        }
        bindings.record(new ExchangeBinding(null, certificateId, ProtocolVersions.CCM_2_4_0,
                CounterpartyRole.CONSUMER, target.consumerBpn(), null, target.consumerUrl()));
        var publication = provider.publish(certificateId, null);
        bindings.record(new ExchangeBinding(publication.exchangeId(), certificateId, ProtocolVersions.CCM_2_4_0,
                CounterpartyRole.CONSUMER, target.consumerBpn(), null, target.consumerUrl()));
        return ResponseEntity.accepted().body(new Ccm240PublishResult(publication.exchangeId(), certificateId));
    }

    /** Where to deliver a provider-initiated v2.4.0 push. */
    public record Ccm240PublishTarget(String consumerBpn, String consumerUrl) {
    }

    /** The v3 exchange opened by a v2.4.0 publish. */
    public record Ccm240PublishResult(String exchangeId, String certificateId) {
    }

    // --- translation helpers -----------------------------------------------------------------------

    private static List<Ccm240Error> toCcm240Errors(List<StatusError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of(new Ccm240Error("Request rejected"));
        }
        return errors.stream().map(e -> new Ccm240Error(e.message())).toList();
    }

    private static List<StatusError> toStatusErrors(Ccm240CertificateStatus.Content content) {
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
        return errors.isEmpty() ? null : errors;
    }

    private byte[] acceptanceEvent(String exchangeId, String certificateId, AcceptanceStatus status,
                                   List<StatusError> errors, Ccm240CertificateStatus message) {
        var header = message.header();
        var senderBpn = header == null ? properties.consumer().bpn() : header.senderBpn();
        var receiverBpn = header == null ? properties.provider().bpn() : header.receiverBpn();
        var messageId = header == null || header.messageId() == null ? UUID.randomUUID().toString() : header.messageId();
        var data = new AcceptanceStatusData(exchangeId, certificateId, status, errors);
        var event = new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                CcmEvents.TYPE_ACCEPTANCE_STATUS,
                "urn:bpn:" + senderBpn,
                receiverBpn,
                messageId,
                OffsetDateTime.now(clock),
                CloudEvent.CONTENT_TYPE_JSON,
                CcmEvents.SCHEMA_ACCEPTANCE_STATUS,
                senderBpn,
                data);
        return mapper.writeValueAsBytes(event);
    }
}
