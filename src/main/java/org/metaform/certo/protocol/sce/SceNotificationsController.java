package org.metaform.certo.protocol.sce;

import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.inbound.SecurityTokenInterceptor;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.ConsumerExchangeService;
import org.metaform.certo.consumer.spi.InboundProtocolEvent;
import org.metaform.certo.protocol.sce.model.SceLifecycleStatus;
import org.metaform.certo.provider.ProviderExchangeService;
import org.metaform.certo.provider.spi.InboundAcceptanceEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

/**
 * The shared SCE {@code POST /notifications} endpoint. The Provider API and the Consumer API each define a
 * {@code notifications} endpoint; Certo hosts both roles at one base URL, so a single endpoint receives all
 * three event types and routes by {@code type}: lifecycle and fulfillment events to the consumer side,
 * acceptance events to the provider side. A batch is accepted or rejected as a unit, so it must be
 * single-direction — the event directions never mix in one sender's batch anyway.
 *
 * <p>Wire concerns live here: envelope parsing, event types, payload decoding, and the SCE sender-identity
 * rule (an event's {@code source} must match the authenticated sender's verified DID).
 */
@RestController
public class SceNotificationsController {

    private final ConsumerExchangeService consumerService;
    private final ProviderExchangeService providerService;
    private final CloudEventCodec codec;

    public SceNotificationsController(ConsumerExchangeService consumerService,
                                      ProviderExchangeService providerService,
                                      CloudEventCodec codec) {
        this.consumerService = consumerService;
        this.providerService = providerService;
        this.codec = codec;
    }

    @PostMapping(path = "/notifications",
            consumes = {CloudEvent.CONTENT_TYPE_CLOUDEVENTS, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> postNotifications(
            @RequestBody byte[] body,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        var consumerEvents = new ArrayList<InboundProtocolEvent>();
        var acceptanceEvents = new ArrayList<InboundAcceptanceEvent>();
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            switch (type) {
                case Sce.TYPE_LIFECYCLE_STATUS -> {
                    var event = codec.decode(node, SceLifecycleStatus.class);
                    requireVerifiedSource(event.source(), requestContext);
                    var data = new LifecycleStatusData(event.data().status(),
                            SceCertificateCodec.toDomain(event.data().certificate()));
                    consumerEvents.add(new InboundProtocolEvent.Lifecycle(data, codec.dedupKey(event)));
                }
                case Sce.TYPE_FULFILLMENT_STATUS -> {
                    var event = codec.decode(node, FulfillmentStatusData.class);
                    requireVerifiedSource(event.source(), requestContext);
                    consumerEvents.add(new InboundProtocolEvent.Fulfillment(event.data(), codec.dedupKey(event)));
                }
                case Sce.TYPE_ACCEPTANCE_STATUS -> {
                    var event = codec.decode(node, AcceptanceStatusData.class);
                    requireVerifiedSource(event.source(), requestContext);
                    acceptanceEvents.add(new InboundAcceptanceEvent(event.data(), codec.dedupKey(event)));
                }
                default -> throw ApiException.badRequest("Unsupported notification event type: " + type);
            }
        }
        if (!consumerEvents.isEmpty() && !acceptanceEvents.isEmpty()) {
            // Each side applies its batch atomically; mixing directions would break accept-or-reject-as-a-unit.
            throw ApiException.badRequest("A notification batch must not mix acceptance events with lifecycle/fulfillment events");
        }
        if (!acceptanceEvents.isEmpty()) {
            providerService.recordAcceptances(acceptanceEvents, requestContext);
        } else {
            consumerService.handleNotifications(consumerEvents, requestContext);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * SCE sender-identity rule: the event's {@code source} must match the authenticated sender (the verified
     * token subject — the counterparty's DID), or the event is rejected.
     */
    private static void requireVerifiedSource(String source, VerifiedRequestContext requestContext) {
        if (source == null || !source.equals(requestContext.subject())) {
            throw ApiException.badRequest("Event source does not match the authenticated sender: " + source);
        }
    }
}
