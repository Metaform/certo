package org.metaform.certo.common.event.nats;

import io.nats.client.JetStream;
import io.nats.client.impl.Headers;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.event.CertificateExchangeStatusChanged;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Publishes {@link CertificateExchangeStatusChanged} to NATS JetStream as a CloudEvents 1.0 event in
 * JSON structured mode — the same shape the EDC runtimes publish through their {@code events-nats}
 * bridge, so a consumer of the platform's {@code edc-events} stream handles Certo's events the same
 * way it handles the connector's.
 *
 * <p>Not a {@code @Component}: it is registered by {@link NatsConfiguration}, which carries the
 * {@code certo.events.nats.enabled} condition. Component-scanning it would register the bean
 * unconditionally and fail the context with a missing {@code JetStream} whenever publishing is off —
 * which is the default.
 *
 * <p><b>Delivery semantics.</b> The listener runs {@code AFTER_COMMIT}, so a transaction that rolls
 * back announces nothing. {@code fallbackExecution = true} is load-bearing rather than defensive:
 * several exchange operations — {@code ProviderExchangeService.pollAcceptance} and {@code publish},
 * {@code ConsumerExchangeService.initiateRequest}, {@code pollRequest} and {@code retrieve} — are
 * declared {@code @Transactional(propagation = NOT_SUPPORTED)} and commit through an inner
 * {@code TransactionTemplate}. By the time the event is delivered there is no surrounding transaction,
 * and without this flag Spring would discard those events silently, losing the provider-initiated
 * publish path entirely.
 *
 * <p><b>Failures are swallowed and logged</b>, matching the EDC bridge. The state change has already
 * committed; propagating a broker failure would fail an API call whose work was actually done.
 */
public class NatsEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NatsEventPublisher.class);

    private final JetStream jetStream;
    private final ObjectMapper mapper;
    private final ParticipantContextStore contextStore;
    private final String source;

    public NatsEventPublisher(JetStream jetStream, ObjectMapper mapper, ParticipantContextStore contextStore, String source) {
        this.jetStream = jetStream;
        this.mapper = mapper;
        this.contextStore = contextStore;
        this.source = source;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CertificateExchangeStatusChanged event) {
        try {
            var subject = event.eventType().subject();
            jetStream.publish(subject, headers(), mapper.writeValueAsBytes(envelope(event)));
            log.debug("Published {} for exchange {}", subject, event.exchangeId());
        } catch (Exception e) {
            // Deliberately broad: a publishing failure must never surface as a failed API call, and
            // must never mask the fact that the transition itself committed.
            log.error("Failed to publish {} for exchange {}",
                    event.eventType().subject(), event.exchangeId(), e);
        }
    }

    /**
     * Wraps the payload in the CloudEvents envelope.
     *
     * <p>{@code source} identifies the EMITTING APPLICATION — certo's hostname — matching every other
     * platform event producer (the EDC bridge builds it as {@code URI.create(hostname)} from its
     * injected {@code Hostname} service). It deliberately does NOT carry the tenant: a consumer
     * de-duplicating on {@code source} + {@code id} needs a stable per-producer value, and tools that
     * group events by origin expect the service, not the participant.
     *
     * <p>The emitting tenant is identified by the CX-0000 §2.1.2-REQUIRED {@code sourcebpn} extension
     * instead, read from its participant context, with {@code participantContextId} in the payload.
     */
    // Package-private so the envelope contract can be asserted without standing up a broker.
    CloudEvent<CertificateExchangeStatusChanged> envelope(CertificateExchangeStatusChanged event) {
        var context = contextStore.find(event.participantContextId()).orElse(null);
        return new CloudEvent<>(
                CloudEvent.SPEC_VERSION,
                event.eventType().type(),
                source,
                event.exchangeId(),
                UUID.randomUUID().toString(),
                event.occurredAt(),
                CloudEvent.CONTENT_TYPE_JSON,
                null,
                // A tenant deleted between the transition committing and this listener running leaves
                // nothing to read; the event still goes out, carrying participantContextId in the data.
                context == null ? null : context.bpn(),
                event);
    }

    private static Headers headers() {
        return new Headers().add("Content-Type", CcmEvents.CONTENT_TYPE);
    }
}
