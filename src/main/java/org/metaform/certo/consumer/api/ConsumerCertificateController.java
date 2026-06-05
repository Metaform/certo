package org.metaform.certo.consumer.api;

import jakarta.validation.Valid;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.consumer.ConsumerCertificateService;
import org.metaform.certo.consumer.api.dto.CertificateAcceptanceStatusResponse;
import org.metaform.certo.consumer.api.dto.ConsumerRequestView;
import org.metaform.certo.consumer.api.dto.InitiateRequest;
import org.metaform.certo.consumer.api.dto.KnownCertificateView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Certificate Consumer Notification API (CX-0135 &sect;4.3). Optional in the spec; implemented
 * here for the demo. DSP control-plane concerns are out of scope.
 */
@RestController
public class ConsumerCertificateController {

    private final ConsumerCertificateService service;

    public ConsumerCertificateController(ConsumerCertificateService service) {
        this.service = service;
    }

    /**
     * {@code POST /certificate-notifications} — receive lifecycle and fulfillment CloudEvents
     * (CX-0135 &sect;4.3.1 / &sect;4.3.2). Accepts a single event or a batch.
     */
    @PostMapping(path = "/certificate-notifications",
            consumes = {CcmEvents.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> postNotifications(@RequestBody byte[] body) {
        service.handleNotifications(body);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /certificate-acceptance-status/{id}} — query the acceptance status (CX-0135 &sect;4.3.3). */
    @GetMapping(path = "/certificate-acceptance-status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(@PathVariable("id") String exchangeId) {
        return service.getAcceptanceStatus(exchangeId);
    }

    // --- consumer-initiated "pull" (demo triggers; the request flow itself is CX-0135 §4.4.1/§4.4.2) ---

    /** {@code POST /consumer/certificate-requests} — open a certificate request on the provider. */
    @PostMapping(path = "/consumer/certificate-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConsumerRequestView> initiate(@Valid @RequestBody InitiateRequest request) {
        var opened = service.initiateRequest(request.certificateType(), request.locationBpns());
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
     * learned about (updated from CREATED/MODIFIED/WITHDRAWN events; demo/inspection).
     */
    @GetMapping(path = "/consumer/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public KnownCertificateView getKnownCertificate(@PathVariable("id") String certificateId) {
        return KnownCertificateView.of(service.getKnownCertificate(certificateId));
    }
}
