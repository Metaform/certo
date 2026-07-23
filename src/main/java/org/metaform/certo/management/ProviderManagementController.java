package org.metaform.certo.management;

import org.metaform.certo.provider.ProviderCatalogService;
import org.metaform.certo.provider.ProviderExchangeService;
import org.metaform.certo.provider.dto.CertificateAdded;
import org.metaform.certo.provider.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.dto.CertificatePublication;
import org.metaform.certo.provider.dto.CertificateRequestPage;
import org.metaform.certo.provider.dto.CertificateRequestQuery;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.dto.ExchangeView;
import org.metaform.certo.provider.dto.NewCertificate;
import org.metaform.certo.provider.dto.NewDocument;
import org.metaform.certo.provider.dto.NewRevision;
import org.metaform.certo.provider.dto.PublishRequest;
import org.metaform.certo.provider.dto.StoredDocument;
import org.springframework.http.HttpStatus;
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
 * Provider-side <b>management</b> control surface — the triggers and inspection views used to drive
 * and observe the app. None of these are part of the CX-0135 wire protocol (a real deployment's
 * certificate-management backend would drive publication/modification/withdrawal and fulfillment); they
 * are kept out of the protocol adapters so those contain only §3.3 / §4.x endpoints.
 *
 * <p>Every operation is scoped to a provider tenant named in the path — {@code
 * /management/v1/participant-contexts/{participantContextId}/…} (the EDC Management API scheme, siglet's
 * {@code /tokens/{participant_context_id}/…} convention). A resource addressed by id must belong to the
 * path tenant, or it is 404; queries return only that tenant's resources.
 */
@RestController
@RequestMapping("/management/v1/participant-contexts/{participantContextId}")
public class ProviderManagementController {

    private final ProviderCatalogService catalog;
    private final ProviderExchangeService exchanges;

    public ProviderManagementController(ProviderCatalogService catalog, ProviderExchangeService exchanges) {
        this.catalog = catalog;
        this.exchanges = exchanges;
    }

    /**
     * {@code POST /documents} — upload a certificate document binary the backend has produced, ahead of the
     * certificate that will reference it. Returns the assigned {@code documentId}.
     */
    @PostMapping(path = "/documents",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredDocument> addDocument(@PathVariable("participantContextId") String participantContextId,
                                                      @RequestBody NewDocument request) {
        var stored = catalog.addDocument(participantContextId,
                request.mediaType(),
                request.language(),
                request.contentBase64());
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * {@code POST /certificates} — the certification-authority backend has issued a certificate: add it to
     * the provider's holdings (referencing documents already uploaded via {@code POST /documents}). A
     * <b>state change only</b>: waiting consumers are notified separately via {@code fulfillable-requests} +
     * {@code fulfill}.
     */
    @PostMapping(path = "/certificates",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateAdded> addCertificate(@PathVariable("participantContextId") String participantContextId,
                                                           @RequestBody NewCertificate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.addCertificate(participantContextId, request));
    }

    /**
     * {@code GET /certificates/{id}/fulfillable-requests} (UC2) — the waiting consumer exchanges this issued
     * certificate covers. The client iterates the result and calls {@code fulfill} on each with that
     * consumer's live {@code flowId}.
     */
    @GetMapping(path = "/certificates/{id}/fulfillable-requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestPage fulfillableRequests(@PathVariable("participantContextId") String participantContextId,
                                                      @PathVariable("id") String certificateId) {
        return exchanges.fulfillableRequests(participantContextId, certificateId);
    }

    /**
     * {@code POST /certificate-requests/query} (UC1) — browse/reconcile the backlog of consumer-initiated
     * exchanges (by default those still {@code CERTIFICATION_REQUESTED}), filtered by the request body.
     */
    @PostMapping(path = "/certificate-requests/query",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestPage queryRequests(@PathVariable("participantContextId") String participantContextId,
                                                @RequestBody(required = false) CertificateRequestQuery query) {
        return exchanges.queryRequests(participantContextId, query);
    }

    /**
     * {@code POST /certificate-requests/{id}/fulfill} — fulfill one waiting exchange with a now-held
     * certificate that covers it and push {@code FULFILLED} to that consumer over its live {@code flowId}.
     */
    @PostMapping(path = "/certificate-requests/{id}/fulfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus fulfillRequest(@PathVariable("participantContextId") String participantContextId,
                                                   @PathVariable("id") String exchangeId,
                                                   @RequestParam(value = "flowId", required = false) String flowId) {
        return exchanges.fulfill(participantContextId, exchangeId, flowId);
    }

    /**
     * {@code POST /certificates/{id}/revisions} — create a new version of the certificate: append a
     * revision with the caller's issued validity + documents (lifecycle {@code MODIFIED}). A <b>state change
     * only</b>; notifying consumers is a separate {@code publish} of a {@code MODIFIED} event (CX-0135 &sect;2.2.4).
     */
    @PostMapping(path = "/certificates/{id}/revisions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateLifecycleResult> addRevision(@PathVariable("participantContextId") String participantContextId,
                                                                  @PathVariable("id") String certificateId,
                                                                  @RequestBody NewRevision request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.addRevision(participantContextId, certificateId, request));
    }

    /**
     * {@code POST /certificate-requests/{id}/fail} — the backend could not issue the certificate: fail a
     * waiting exchange (optionally with a {@code reason}) and push the terminal status to the consumer.
     */
    @PostMapping(path = "/certificate-requests/{id}/fail", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus failRequest(@PathVariable("participantContextId") String participantContextId,
                                                @PathVariable("id") String exchangeId,
                                                @RequestParam(value = "flowId", required = false) String flowId,
                                                @RequestParam(value = "reason", required = false) String reason) {
        return exchanges.failRequest(participantContextId, exchangeId, flowId, reason);
    }

    /**
     * {@code POST /certificate-requests/{id}/decline} — the provider declines the request (a business
     * decision, optionally with a {@code reason}): a waiting exchange &rarr; {@code DECLINED}, pushed to the consumer.
     */
    @PostMapping(path = "/certificate-requests/{id}/decline", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus declineRequest(@PathVariable("participantContextId") String participantContextId,
                                                   @PathVariable("id") String exchangeId,
                                                   @RequestParam(value = "flowId", required = false) String flowId,
                                                   @RequestParam(value = "reason", required = false) String reason) {
        return exchanges.declineRequest(participantContextId, exchangeId, flowId, reason);
    }

    /**
     * {@code POST /certificates/{id}/publish} — provider-initiated push of a held certificate. The
     * {@link PublishRequest} body selects the protocol {@code protocolVersion} (default {@code 3.0.0} to the native
     * consumer; {@code 2.4.0} to a caller-named consumer), whether the certificate is sent {@code embedded}
     * (full content inline) or by reference, and the {@code revision}. An empty body publishes the latest
     * revision to the native consumer, by reference.
     */
    @PostMapping(path = "/certificates/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificatePublication> publish(@PathVariable("participantContextId") String participantContextId,
                                                          @PathVariable("id") String certificateId,
                                                          @RequestBody(required = false) PublishRequest request) {
        return ResponseEntity.accepted().body(exchanges.publish(participantContextId, certificateId, request));
    }

    /**
     * {@code POST /certificates/{id}/withdraw} — withdraw a certificate (lifecycle {@code WITHDRAWN}). A
     * <b>state change only</b>; notifying consumers is a separate {@code publish} of a {@code WITHDRAWN} event.
     */
    @PostMapping(path = "/certificates/{id}/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateLifecycleResult withdraw(@PathVariable("participantContextId") String participantContextId,
                                               @PathVariable("id") String certificateId) {
        return catalog.withdraw(participantContextId, certificateId);
    }

    /**
     * {@code GET /certificate-exchanges/{id}} — the provider's full view of an exchange, both phases.
     */
    @GetMapping(path = "/certificate-exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView getExchange(@PathVariable("participantContextId") String participantContextId,
                                    @PathVariable("id") String exchangeId) {
        return exchanges.getExchangeView(participantContextId, exchangeId);
    }

    /**
     * {@code POST /certificate-exchanges/{id}/poll-acceptance} — recover a possibly-lost acceptance by pulling
     * the consumer's acceptance-status over {@code flowId} and recording any verdict on the exchange (the pull
     * fallback to the consumer's best-effort push). Native v3 consumers only. Returns the refreshed view.
     */
    @PostMapping(path = "/certificate-exchanges/{id}/poll-acceptance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView pollAcceptance(@PathVariable("participantContextId") String participantContextId,
                                       @PathVariable("id") String exchangeId,
                                       @RequestParam(value = "flowId", required = false) String flowId) {
        return exchanges.pollAcceptance(participantContextId, exchangeId, flowId);
    }
}
