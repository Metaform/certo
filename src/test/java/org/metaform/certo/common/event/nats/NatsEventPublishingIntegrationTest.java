package org.metaform.certo.common.event.nats;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.metaform.certo.provider.domain.ProviderCertificateExchange;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that a committed status change reaches NATS.
 *
 * <p>The path under test spans four things a unit test cannot join up: the aggregate recording the
 * change, Spring Data draining it on {@code save()}, the transaction manager releasing it after
 * commit, and the JetStream client putting it on a subject the stream actually captures. It is also
 * the only test that would catch a subject the stream does not match — a publish that silently goes
 * nowhere.
 *
 * <p>Requires Docker. Testcontainers aborts (not fails) the class when none is available, so a
 * Docker-less machine still gets a green build.
 */
@SpringBootTest
@Testcontainers
class NatsEventPublishingIntegrationTest {

    private static final String STREAM = "edc-events";
    private static final String SUBJECT_FILTER = "events.certificate.exchange.>";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>(DockerImageName.parse("nats:alpine"))
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @DynamicPropertySource
    static void natsProperties(DynamicPropertyRegistry registry) {
        registry.add("certo.events.nats.enabled", () -> true);
        registry.add("certo.events.nats.url", () -> "nats://%s:%d".formatted(NATS.getHost(), NATS.getMappedPort(4222)));
        registry.add("certo.events.nats.stream", () -> STREAM);
        // The platform's nats-bootstrap job owns this stream in a real deployment; here the app creates it.
        registry.add("certo.events.nats.create-stream", () -> true);
        // Pin the CloudEvents source so it can be asserted; unset it resolves to this machine's hostname.
        registry.add("certo.events.nats.source", () -> "certo-under-test");
    }

    @Autowired
    private ProviderCertificateExchangeStore exchangeStore;
    @Autowired
    private ParticipantContextStore contextStore;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aCommittedExchangeLifecycleIsPublishedInOrder() throws Exception {
        var tenantId = "pctx-" + UUID.randomUUID();
        contextStore.save(new ParticipantContext(tenantId, "BPNL0000000009ZZ", "urn:bpn:BPNL0000000009ZZ",
                "did:web:events-test-" + UUID.randomUUID()));

        try (var connection = Nats.connect("nats://%s:%d".formatted(NATS.getHost(), NATS.getMappedPort(4222)))) {
            // Subscribe before producing: the stream retains on Interest policy, so a message published
            // with no registered consumer is discarded rather than queued.
            ensureStream(connection);
            var subscription = connection.jetStream().subscribe(SUBJECT_FILTER);

            var exchangeId = "ex-" + UUID.randomUUID();
            var exchange = new ProviderCertificateExchange(exchangeId, tenantId, "cert-1", 1,
                    "BPNL-CONSUMER", "did:web:consumer", FulfillmentStatus.REQUESTED);
            exchangeStore.save(exchange);

            var loaded = exchangeStore.findById(exchangeId).orElseThrow();
            loaded.transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null);
            exchangeStore.save(loaded);

            loaded = exchangeStore.findById(exchangeId).orElseThrow();
            loaded.transitionFulfillment(FulfillmentStatus.FULFILLED, null);
            loaded.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
            exchangeStore.save(loaded);

            var received = new ArrayList<JsonNode>();
            for (var i = 0; i < 4; i++) {
                var message = subscription.nextMessage(Duration.ofSeconds(10));
                assertThat(message).as("expected 4 events, got %d", received.size()).isNotNull();
                message.ack();
                received.add(mapper.readTree(message.getData()));
            }

            assertThat(received).extracting(node -> node.get("type").asString()).containsExactly(
                    "org.catena-x.ccm.CertificateExchangeRequested.v1",
                    "org.catena-x.ccm.CertificateExchangeAcknowledged.v1",
                    "org.catena-x.ccm.CertificateExchangeFulfilled.v1",
                    "org.catena-x.ccm.CertificateExchangeAccepted.v1");

            // source names the emitting application (like every other platform producer); the tenant
            // travels in sourcebpn and in the payload.
            var first = received.getFirst();
            assertThat(first.get("source").asString()).isEqualTo("certo-under-test");
            assertThat(first.get("sourcebpn").asString()).isEqualTo("BPNL0000000009ZZ");
            assertThat(first.get("specversion").asString()).isEqualTo("1.0");
            assertThat(first.get("data").get("exchangeId").asString()).isEqualTo(exchangeId);
            assertThat(first.get("data").get("previousStatus")).isNull();
            assertThat(received.get(1).get("data").get("previousStatus").asString()).isEqualTo("REQUESTED");
            assertThat(received.getLast().get("data").get("role").asString()).isEqualTo("PROVIDER");
            assertThat(received.getLast().get("data").get("phase").asString()).isEqualTo("ACCEPTANCE");

            assertThat(subscription.nextMessage(Duration.ofMillis(500)))
                    .as("no further events expected")
                    .isNull();
        }
    }

    @Test
    void rolledBackWorkPublishesNothing() throws Exception {
        try (var connection = Nats.connect("nats://%s:%d".formatted(NATS.getHost(), NATS.getMappedPort(4222)))) {
            ensureStream(connection);
            var subscription = connection.jetStream().subscribe(SUBJECT_FILTER);
            drain(subscription);

            // Save inside a transaction that then rolls back. The aggregate records the change and
            // Spring Data drains it, so the event IS raised — after-commit delivery is the only thing
            // standing between a rolled-back transition and a consumer being told it happened.
            new TransactionTemplate(transactionManager).execute(status -> {
                exchangeStore.save(new ProviderCertificateExchange("ex-" + UUID.randomUUID(), "pctx-rollback",
                        "cert-1", 1, "BPNL-CONSUMER", "did:web:consumer", FulfillmentStatus.REQUESTED));
                status.setRollbackOnly();
                return null;
            });

            assertThat(subscription.nextMessage(Duration.ofSeconds(1)))
                    .as("a rolled-back transition must not be published")
                    .isNull();
        }
    }

    /** Creates the stream if the app has not yet (bean init order is not guaranteed relative to the test). */
    private static void ensureStream(Connection connection) throws Exception {
        var jsm = connection.jetStreamManagement();
        var existing = jsm.getStreamNames();
        if (!existing.contains(STREAM)) {
            jsm.addStream(StreamConfiguration.builder()
                    .name(STREAM)
                    .subjects(SUBJECT_FILTER)
                    .storageType(StorageType.Memory)
                    .build());
        }
    }

    /** Consumes anything left over from an earlier test so this one starts from a quiet subscription. */
    private static void drain(JetStreamSubscription subscription) throws Exception {
        for (var message = subscription.nextMessage(Duration.ofMillis(200));
             message != null;
             message = subscription.nextMessage(Duration.ofMillis(200))) {
            message.ack();
        }
    }
}
