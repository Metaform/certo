package org.metaform.certo.management;

import jakarta.validation.Valid;
import org.metaform.certo.consumer.ConsumerCatalogService;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.consumer.dto.AcceptRequest;
import org.metaform.certo.consumer.dto.ConsumerExchangePage;
import org.metaform.certo.consumer.dto.ConsumerExchangeQuery;
import org.metaform.certo.consumer.dto.ConsumerRequestView;
import org.metaform.certo.consumer.dto.InitiateRequest;
import org.metaform.certo.consumer.dto.KnownCertificateView;
import org.metaform.certo.consumer.dto.RetrievedCertificateView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side <b>management</b> control surface — the app's own endpoints for driving a
 * consumer-initiated pull and inspecting local state. These are not part of the CX-0135 wire protocol
 * (the consumer's protocol surface is only {@code POST /certificate-notifications} and
 * {@code GET /certificate-acceptance-status/{id}}); the underlying provider-facing request flow they
 * trigger is CX-0135 &sect;4.4.1 / &sect;4.4.2.
 *
 * <p>Every operation is scoped to a consumer tenant named in the path — {@code
 * /management/v1/participant-contexts/{participantContextId}/consumer/…} (siglet's
 * {@code /tokens/{participant_context_id}/…} convention). An exchange or certificate addressed by id must
 * belong to the path tenant, or it is 404; queries return only that tenant's exchanges.
 */
@RestController
@RequestMapping("/management/v1/participant-contexts/{participantContextId}/consumer")
public class ConsumerManagementController {

    private final ConsumerExchangeService service;
    private final ConsumerCatalogService catalog;

    public ConsumerManagementController(ConsumerExchangeService service, ConsumerCatalogService catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    /** {@code POST /consumer/certificate-requests} — open a certificate request on the provider. */
    @PostMapping(path = "/certificate-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConsumerRequestView> initiate(@PathVariable("participantContextId") String participantContextId,
                                                        @Valid @RequestBody InitiateRequest request) {
        var opened = service.initiateRequest(participantContextId, request.providerBpn(), request.providerDid(),
                request.certificateType(), request.flowId(), request.certifiedLocations());
        return ResponseEntity.accepted().body(ConsumerRequestView.of(opened));
    }

    /** {@code GET /consumer/certificate-requests/{id}} — the consumer's tracked fulfillment status. */
    @GetMapping(path = "/certificate-requests/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConsumerRequestView getRequest(@PathVariable("participantContextId") String participantContextId,
                                          @PathVariable("id") String exchangeId) {
        return ConsumerRequestView.of(service.getRequest(participantContextId, exchangeId));
    }

    /** {@code POST /consumer/certificate-requests/{id}/poll} — poll the provider for fulfillment status. */
    @PostMapping(path = "/certificate-requests/{id}/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConsumerRequestView pollRequest(@PathVariable("participantContextId") String participantContextId,
                                           @PathVariable("id") String exchangeId,
                                           @RequestParam(value = "flowId", required = false) String flowId) {
        return ConsumerRequestView.of(service.pollRequest(participantContextId, exchangeId, flowId));
    }

    /**
     * {@code POST /consumer/exchanges/query} — the consumer-side reconciliation query: exchanges awaiting the
     * caller's action (by default those {@code FULFILLED} but not yet accepted). The safety net for a dropped
     * inbound-notification callback.
     */
    @PostMapping(path = "/exchanges/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConsumerExchangePage queryExchanges(@PathVariable("participantContextId") String participantContextId,
                                               @RequestBody(required = false) ConsumerExchangeQuery query) {
        return service.queryExchanges(participantContextId, query);
    }

    /**
     * {@code POST /consumer/exchanges/{id}/retrieve} — pull the exchange's certificate from the provider
     * over {@code flowId}, for the caller to inspect before deciding acceptance. Typically driven by a
     * client reacting to an inbound-notification callback under security.
     */
    @PostMapping(path = "/exchanges/{id}/retrieve", produces = MediaType.APPLICATION_JSON_VALUE)
    public RetrievedCertificateView retrieve(@PathVariable("participantContextId") String participantContextId,
                                             @PathVariable("id") String exchangeId,
                                             @RequestParam(value = "flowId", required = false) String flowId) {
        return RetrievedCertificateView.of(service.retrieve(participantContextId, exchangeId, flowId));
    }

    /**
     * {@code POST /consumer/exchanges/{id}/accept} — record the caller's acceptance decision and report it
     * to the provider over {@code flowId}.
     */
    @PostMapping(path = "/exchanges/{id}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> accept(@PathVariable("participantContextId") String participantContextId,
                                       @PathVariable("id") String exchangeId, @RequestBody AcceptRequest request) {
        service.accept(participantContextId, exchangeId, request.status(), request.errors(), request.flowId());
        return ResponseEntity.accepted().build();
    }

    /**
     * {@code GET /consumer/certificates/{id}} — the consumer's lifecycle view of a certificate it has
     * learned about (updated from CREATED/MODIFIED/WITHDRAWN events).
     */
    @GetMapping(path = "/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnownCertificateView getKnownCertificate(@PathVariable("participantContextId") String participantContextId,
                                                    @PathVariable("id") String certificateId) {
        return KnownCertificateView.of(catalog.getKnownCertificate(participantContextId, certificateId));
    }
}
