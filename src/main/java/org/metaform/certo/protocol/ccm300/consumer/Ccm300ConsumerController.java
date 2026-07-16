package org.metaform.certo.protocol.ccm300.consumer;

import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.security.SecurityTokenInterceptor;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.consumer.dto.CertificateAcceptanceStatusResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Certificate Consumer Notification API (CX-0135 &sect;4.3). Optional in the spec; implemented
 * here for completeness.
 */
@RestController
public class Ccm300ConsumerController {

    private final ConsumerExchangeService service;

    public Ccm300ConsumerController(ConsumerExchangeService service) {
        this.service = service;
    }

    /**
     * {@code POST /certificate-notifications} — receive lifecycle and fulfillment CloudEvents
     * (CX-0135 &sect;4.3.1 / &sect;4.3.2). Accepts a single event or a batch.
     */
    @PostMapping(path = "/certificate-notifications",
            consumes = {CcmEvents.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> postNotifications(
            @RequestBody byte[] body,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        service.handleNotifications(body, requestContext);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /certificate-acceptance-status/{id}} — query the acceptance status (CX-0135 &sect;4.3.3). */
    @GetMapping(path = "/certificate-acceptance-status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(@PathVariable("id") String exchangeId,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        return service.getAcceptanceStatus(requestContext.participantContextId(), exchangeId);
    }
}
