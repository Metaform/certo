package org.metaform.certo.management;

import jakarta.validation.Valid;
import org.metaform.certo.consumer.ConsumerCertificateService;
import org.metaform.certo.consumer.dto.ConsumerRequestView;
import org.metaform.certo.consumer.dto.InitiateRequest;
import org.metaform.certo.consumer.dto.KnownCertificateView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-side <b>management</b> control surface — the app's own endpoints for driving a
 * consumer-initiated pull and inspecting local state. These are not part of the CX-0135 wire protocol
 * (the consumer's protocol surface is only {@code POST /certificate-notifications} and
 * {@code GET /certificate-acceptance-status/{id}}); the underlying provider-facing request flow they
 * trigger is CX-0135 &sect;4.4.1 / &sect;4.4.2. All endpoints are segregated under the versioned
 * {@code /management/v1} path (EDC Management API scheme: {@code /management/v<major>}) so they never
 * share URL space with the protocol; the protocol adapter stays a pure §4.3 surface.
 */
@RestController
@RequestMapping("/management/v1")
public class ConsumerManagementController {

    private final ConsumerCertificateService service;

    public ConsumerManagementController(ConsumerCertificateService service) {
        this.service = service;
    }

    /** {@code POST /consumer/certificate-requests} — open a certificate request on the provider. */
    @PostMapping(path = "/consumer/certificate-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConsumerRequestView> initiate(@Valid @RequestBody InitiateRequest request) {
        var opened = service.initiateRequest(request.certificateType(), request.certifiedLocations());
        return ResponseEntity.accepted().body(ConsumerRequestView.of(opened));
    }

    /** {@code GET /consumer/certificate-requests/{id}} — the consumer's tracked fulfillment status. */
    @GetMapping(path = "/consumer/certificate-requests/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConsumerRequestView getRequest(@PathVariable("id") String exchangeId) {
        return ConsumerRequestView.of(service.getRequest(exchangeId));
    }

    /** {@code POST /consumer/certificate-requests/{id}/poll} — poll the provider for fulfillment status. */
    @PostMapping(path = "/consumer/certificate-requests/{id}/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConsumerRequestView pollRequest(@PathVariable("id") String exchangeId) {
        return ConsumerRequestView.of(service.pollRequest(exchangeId));
    }

    /**
     * {@code GET /consumer/certificates/{id}} — the consumer's lifecycle view of a certificate it has
     * learned about (updated from CREATED/MODIFIED/WITHDRAWN events).
     */
    @GetMapping(path = "/consumer/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnownCertificateView getKnownCertificate(@PathVariable("id") String certificateId) {
        return KnownCertificateView.of(service.getKnownCertificate(certificateId));
    }
}
