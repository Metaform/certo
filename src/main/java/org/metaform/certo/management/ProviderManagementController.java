package org.metaform.certo.management;

import org.metaform.certo.provider.ProviderCertificateService;
import org.metaform.certo.provider.dto.CertificateAdded;
import org.metaform.certo.provider.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.dto.CertificatePublication;
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
 * are kept out of the protocol adapters so those contain only §3.3 / §4.x endpoints. All endpoints are
 * segregated under the versioned {@code /management/v1} path so they never share URL space with the
 * protocol (the version segment follows the EDC Management API scheme: {@code /management/v<major>}).
 */
@RestController
@RequestMapping("/management/v1")
public class ProviderManagementController {

    private final ProviderCertificateService service;

    public ProviderManagementController(ProviderCertificateService service) {
        this.service = service;
    }

    /**
     * {@code POST /documents} — upload a certificate document binary the backend has produced, ahead of the
     * certificate that will reference it. Returns the assigned {@code documentId}.
     */
    @PostMapping(path = "/documents",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredDocument> addDocument(@RequestBody NewDocument request) {
        var stored = service.addDocument(request.mediaType(), request.language(), request.contentBase64());
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * {@code POST /certificates} — the certification-authority backend has issued a certificate: add it to
     * the provider's holdings (referencing documents already uploaded via {@code POST /documents}) and
     * fulfill every waiting exchange it satisfies (notifying those consumers).
     */
    @PostMapping(path = "/certificates",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateAdded> addCertificate(@RequestBody NewCertificate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCertificate(request));
    }

    /**
     * {@code POST /certificates/{id}/revisions} — create a new version of the certificate: append a
     * revision with the caller's issued validity + documents (lifecycle {@code MODIFIED}). A <b>state change
     * only</b>; notifying consumers is a separate {@code publish} of a {@code MODIFIED} event (CX-0135 &sect;2.2.4).
     */
    @PostMapping(path = "/certificates/{id}/revisions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateLifecycleResult> addRevision(@PathVariable("id") String certificateId,
                                                                  @RequestBody NewRevision request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addRevision(certificateId, request));
    }

    /**
     * {@code POST /certificate-requests/{id}/fail} — the backend could not issue the certificate: fail a
     * waiting exchange (optionally with a {@code reason}) and push the terminal status to the consumer.
     */
    @PostMapping(path = "/certificate-requests/{id}/fail", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus failRequest(@PathVariable("id") String exchangeId,
                                                @RequestParam(value = "reason", required = false) String reason) {
        return service.failRequest(exchangeId, reason);
    }

    /**
     * {@code POST /certificate-requests/{id}/decline} — the provider declines the request (a business
     * decision, optionally with a {@code reason}): a waiting exchange &rarr; {@code DECLINED}, pushed to the consumer.
     */
    @PostMapping(path = "/certificate-requests/{id}/decline", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus declineRequest(@PathVariable("id") String exchangeId,
                                                   @RequestParam(value = "reason", required = false) String reason) {
        return service.declineRequest(exchangeId, reason);
    }

    /**
     * {@code POST /certificates/{id}/publish} — provider-initiated push of a held certificate. The
     * {@link PublishRequest} body selects the protocol {@code protocolVersion} (default {@code 3.0.0} to the native
     * consumer; {@code 2.4.0} to a caller-named consumer), whether the certificate is sent {@code embedded}
     * (full content inline) or by reference, and the {@code revision}. An empty body publishes the latest
     * revision to the native consumer, by reference.
     */
    @PostMapping(path = "/certificates/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificatePublication> publish(@PathVariable("id") String certificateId,
                                                          @RequestBody(required = false) PublishRequest request) {
        return ResponseEntity.accepted().body(service.publish(certificateId, request));
    }

    /**
     * {@code POST /certificates/{id}/withdraw} — withdraw a certificate (lifecycle {@code WITHDRAWN}). A
     * <b>state change only</b>; notifying consumers is a separate {@code publish} of a {@code WITHDRAWN} event.
     */
    @PostMapping(path = "/certificates/{id}/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateLifecycleResult withdraw(@PathVariable("id") String certificateId) {
        return service.withdraw(certificateId);
    }

    /** {@code GET /certificate-exchanges/{id}} — the provider's full view of an exchange, both phases. */
    @GetMapping(path = "/certificate-exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView getExchange(@PathVariable("id") String exchangeId) {
        return service.getExchangeView(exchangeId);
    }
}
