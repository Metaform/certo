package org.metaform.certo.common.event.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.metaform.certo.common.event.ExchangeEventType;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the NATS connection and JetStream context when {@code certo.events.nats.enabled=true}. Left
 * disabled (the default) the app starts with no broker dependency, and no publisher bean exists.
 *
 * <p>Connection options mirror the platform's other publishers (the EDC {@code events-nats} bridge and
 * the CX-VE onboarding API): reconnect forever, so a NATS restart does not take the app down with it.
 */
@Configuration
@EnableConfigurationProperties(NatsProperties.class)
@ConditionalOnProperty(prefix = "certo.events.nats", name = "enabled", havingValue = "true")
public class NatsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NatsConfiguration.class);
    /** NATS API error code for "stream name already in use". */
    private static final int ERR_STREAM_NAME_IN_USE = 10058;

    @Bean(destroyMethod = "close")
    public Connection natsConnection(NatsProperties properties) throws IOException, InterruptedException {
        var options = new Options.Builder()
                .server(properties.url())
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1))
                .pingInterval(Duration.ofSeconds(20))
                .maxPingsOut(5);
        if (properties.hasNkeyAuth()) {
            options.authHandler(new NKeyAuthHandler(Path.of(properties.nkeySeedPath())));
            log.info("Connecting to NATS at {} with NKey auth", properties.url());
        } else {
            log.info("Connecting to NATS at {} without authentication", properties.url());
        }
        return Nats.connect(options.build());
    }

    @Bean
    public JetStream jetStream(Connection connection, NatsProperties properties) throws IOException, JetStreamApiException {
        if (properties.createStream()) {
            createStreamIfAbsent(connection, properties);
        }
        return connection.jetStream();
    }

    /**
     * The publisher lives here rather than being component-scanned so that this class's
     * {@code @ConditionalOnProperty} is the single switch: with publishing off there is no
     * {@link JetStream} bean, and a scanned publisher would fail the context looking for one.
     */
    @Bean
    public NatsEventPublisher natsEventPublisher(JetStream jetStream,
                                                 ObjectMapper mapper,
                                                 ParticipantContextStore contextStore,
                                                 NatsProperties properties) {
        var source = resolveSource(properties);
        log.info("Publishing certificate-exchange events with CloudEvents source '{}'", source);
        return new NatsEventPublisher(jetStream, mapper, contextStore, source);
    }

    /**
     * The CloudEvents {@code source} for every event this app emits: its hostname, matching the EDC
     * runtimes (their events-nats bridge uses the injected {@code Hostname} service). In Kubernetes
     * HOSTNAME is the pod name, which is what identifies the producing instance.
     *
     * <p>Overridable via {@code certo.events.nats.source} for deployments that want a stable logical
     * name rather than a per-pod one.
     */
    private static String resolveSource(NatsProperties properties) {
        if (properties.source() != null && !properties.source().isBlank()) {
            return properties.source();
        }
        var fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Never fail startup over a cosmetic attribute; "localhost" is what EDC's Hostname
            // service defaults to as well.
            log.warn("Could not resolve the local hostname for the CloudEvents source, using 'localhost'", e);
            return "localhost";
        }
    }

    /**
     * Creates the stream when it is missing. Development convenience only: in the platform the
     * {@code edc-events} stream is owned by the {@code nats-bootstrap} job and this publisher's NATS
     * user has no stream-management rights, so {@code createStream} must stay false there.
     *
     * <p>Interest retention matches the platform's stream: a message is removed once every registered
     * consumer has taken it.
     */
    private void createStreamIfAbsent(Connection connection, NatsProperties properties) throws IOException, JetStreamApiException {
        var config = StreamConfiguration.builder()
                .name(properties.stream())
                .subjects(ExchangeEventType.SUBJECT_PREFIX + ">")
                .storageType(StorageType.Memory)
                .retentionPolicy(RetentionPolicy.Interest)
                .build();
        try {
            connection.jetStreamManagement().addStream(config);
            log.info("Created NATS stream '{}'", properties.stream());
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() != ERR_STREAM_NAME_IN_USE) {
                throw e;
            }
            log.debug("NATS stream '{}' already exists, leaving it as is", properties.stream());
        }
    }
}
