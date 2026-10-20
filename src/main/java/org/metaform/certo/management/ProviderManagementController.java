package org.metaform.certo.management;

import org.metaform.certo.provider.ProviderCatalogService;
import org.metaform.certo.provider.ProviderExchangeService;
import org.metaform.certo.provider.dto.CertificateAdded;
import org.metaform.certo.provider.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.dto.LifecycleNotification;
import org.metaform.certo.provider.dto.CertificateRequestPage;
import org.metaform.certo.provider.dto.CertificateRequestQuery;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.dto.ExchangeView;
import org.metaform.certo.provider.dto.NewCertificate;
import org.metaform.certo.provider.dto.NewDocument;
import org.metaform.certo.provider.dto.NewRevision;
import org.metaform.certo.provider.dto.NotifyRequest;
import org.metaform.certo.provider.dto.StoredDocument;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * and observe the app. None of these are part of the the SCE specification wire protocol (a real deployment's
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
    private final ProviderCatalogService catalogService;
    private final ProviderExchangeService exchangeService;

    public ProviderManagementController(ProviderCatalogService catalogService, ProviderExchangeService exchangeService) {
        this.catalogService = catalogService;
        this.exchangeService = exchangeService;
    }

    /**
     * {@code POST /documents} — upload a certificate document binary the backend has produced, ahead of the
     * certificate that will reference it. Returns the assigned {@code documentId}.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/documents",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredDocument> addDocument(@PathVariable("participantContextId") String participantContextId,
                                                      @RequestBody NewDocument request) {
        var stored = catalogService.addDocument(participantContextId,
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
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificates",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateAdded> addCertificate(@PathVariable("participantContextId") String participantContextId,
                                                           @RequestBody NewCertificate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addCertificate(participantContextId, request));
    }

    /**
     * {@code GET /certificates/{id}/fulfillable-requests} (UC2) — the waiting consumer exchanges this issued
     * certificate covers. The client iterates the result and calls {@code fulfill} on each with that
     * consumer's live {@code flowId}.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:read')")
    @GetMapping(path = "/certificates/{id}/fulfillable-requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestPage fulfillableRequests(@PathVariable("participantContextId") String participantContextId,
                                                      @PathVariable("id") String certificateId) {
        return exchangeService.fulfillableRequests(participantContextId, certificateId);
    }

    /**
     * {@code POST /certificate-requests/query} (UC1) — browse/reconcile the backlog of consumer-initiated
     * exchanges (by default those still {@code CERTIFICATE_REQUESTED}), filtered by the request body.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:read')")
    @PostMapping(path = "/certificate-requests/query",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestPage queryRequests(@PathVariable("participantContextId") String participantContextId,
                                                @RequestBody(required = false) CertificateRequestQuery query) {
        return exchangeService.queryRequests(participantContextId, query);
    }

    /**
     * {@code POST /certificate-requests/{id}/fulfill} — fulfill one waiting exchange with a now-held
     * certificate that covers it and push {@code FULFILLED} to that consumer over its live {@code flowId}.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificate-requests/{id}/fulfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus fulfillRequest(@PathVariable("participantContextId") String participantContextId,
                                                   @PathVariable("id") String exchangeId,
                                                   @RequestParam(value = "flowId", required = true) String flowId) {
        return exchangeService.fulfill(participantContextId, exchangeId, flowId);
    }

    /**
     * {@code POST /certificates/{id}/revisions} — create a new version of the certificate: append a
     * revision with the caller's issued validity + documents (lifecycle {@code MODIFIED}). A <b>state change
     * only</b>; notifying consumers is a separate {@code notify} per counterparty.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificates/{id}/revisions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateLifecycleResult> addRevision(@PathVariable("participantContextId") String participantContextId,
                                                                  @PathVariable("id") String certificateId,
                                                                  @RequestBody NewRevision request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.addRevision(participantContextId, certificateId, request));
    }

    /**
     * {@code POST /certificate-requests/{id}/decline} — the provider declines the request (a business
     * decision, optionally with a {@code reason}): a waiting exchange &rarr; {@code DECLINED}, pushed to the consumer.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificate-requests/{id}/decline", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus declineRequest(@PathVariable("participantContextId") String participantContextId,
                                                   @PathVariable("id") String exchangeId,
                                                   @RequestParam(value = "flowId", required = true) String flowId,
                                                   @RequestParam(value = "reason", required = false) String reason) {
        return exchangeService.declineRequest(participantContextId, exchangeId, flowId, reason);
    }

    /**
     * {@code POST /certificates/{id}/notify} — single-target push of the certificate's current lifecycle
     * state to the <b>one</b> consumer named in the {@link NotifyRequest}. Certo keeps no counterparty
     * registry: to reach several consumers, call once per consumer; each call returns that one delivery's
     * outcome, so a failure is retried per counterparty.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificates/{id}/notify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LifecycleNotification> notify(@PathVariable("participantContextId") String participantContextId,
                                                        @PathVariable("id") String certificateId,
                                                        @RequestBody NotifyRequest request) {
        return ResponseEntity.accepted().body(exchangeService.notify(participantContextId, certificateId, request));
    }

    /**
     * {@code POST /certificates/{id}/withdraw} — withdraw a certificate (lifecycle {@code WITHDRAWN}). A
     * <b>state change only</b>; notifying consumers is a separate {@code notify} per counterparty.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificates/{id}/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateLifecycleResult withdraw(@PathVariable("participantContextId") String participantContextId,
                                               @PathVariable("id") String certificateId) {
        return catalogService.withdraw(participantContextId, certificateId);
    }

    /**
     * {@code GET /certificate-exchanges/{id}} — the provider's full view of an exchange, both phases.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:read')")
    @GetMapping(path = "/certificate-exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView getExchange(@PathVariable("participantContextId") String participantContextId,
                                    @PathVariable("id") String exchangeId) {
        return exchangeService.getExchangeView(participantContextId, exchangeId);
    }

    /**
     * {@code POST /certificate-exchanges/{id}/poll-acceptance} — recover a possibly-lost acceptance by pulling
     * the consumer's acceptance-status over {@code flowId} and recording any verdict on the exchange (the pull
     * fallback to the consumer's best-effort push). Native v3 consumers only. Returns the refreshed view.
     */
    @PreAuthorize("@mgmtScopes.can(authentication, 'provider:write')")
    @PostMapping(path = "/certificate-exchanges/{id}/poll-acceptance", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView pollAcceptance(@PathVariable("participantContextId") String participantContextId,
                                       @PathVariable("id") String exchangeId,
                                       @RequestParam(value = "flowId", required = true) String flowId) {
        return exchangeService.pollAcceptance(participantContextId, exchangeId, flowId);
    }
}
