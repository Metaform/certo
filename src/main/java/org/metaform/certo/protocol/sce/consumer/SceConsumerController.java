package org.metaform.certo.protocol.sce.consumer;

import org.metaform.certo.common.security.inbound.SecurityTokenInterceptor;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.consumer.dto.CertificateAcceptanceStatusResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SCE Certificate Consumer API's acceptance-status query. Notifications arrive on the shared
 * {@code POST /notifications} endpoint ({@link org.metaform.certo.protocol.sce.SceNotificationsController}).
 */
@RestController
public class SceConsumerController {
    private final ConsumerExchangeService service;

    public SceConsumerController(ConsumerExchangeService service) {
        this.service = service;
    }

    /**
     * {@code GET /acceptance-status/{id}} — the pull counterpart of the acceptance status event: the
     * provider's fallback and recovery path for a lost push. 404 until an acceptance status is reported.
     */
    @GetMapping(path = "/acceptance-status/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateAcceptanceStatusResponse getAcceptanceStatus(@PathVariable("id") String exchangeId,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        return service.getAcceptanceStatus(requestContext.participantContextId(), exchangeId);
    }
}
