package org.metaform.certo.common.event.nats;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for publishing certificate-exchange events to NATS JetStream.
 *
 * <p>Defaults target the platform: the shared {@code edc-events} stream (created by the platform's
 * {@code nats-bootstrap} job with subjects {@code events.>}) and the Vault-delivered NKey seed at
 * {@code /vault/secrets/nats.nk}. <b>Disabled by default</b>, so the app, the test suite and any
 * deployment that has not opted in run with no broker dependency at all.
 *
 * @param enabled       whether to connect to NATS and publish at all
 * @param url           NATS server URL
 * @param stream        the JetStream stream events are expected to land on; only used when
 *                      {@code createStream} is set, since publishing addresses a subject, not a stream
 * @param createStream  create {@code stream} on startup if absent. For standalone development only —
 *                      in the platform the stream is owned by the {@code nats-bootstrap} job, and the
 *                      publisher's NATS user is not permitted to manage streams
 * @param nkeySeedPath  path to the ed25519 NKey seed; blank connects unauthenticated (local dev, or a
 *                      cluster with NATS auth switched off)
 * @param source        CloudEvents {@code source} for emitted events — the emitting application, as
 *                      in every other platform producer. Blank resolves to the hostname (the pod
 *                      name under Kubernetes); set it for a stable logical name instead
 */
@ConfigurationProperties(prefix = "certo.events.nats")
public record NatsProperties(
        boolean enabled,
        String url,
        String stream,
        boolean createStream,
        String nkeySeedPath,
        String source
) {

    public NatsProperties {
        if (url == null || url.isBlank()) {
            url = "nats://localhost:4222";
        }
        if (stream == null || stream.isBlank()) {
            stream = "edc-events";
        }
    }

    /** An NKey seed path is optional (absent → connect unauthenticated). */
    public boolean hasNkeyAuth() {
        return nkeySeedPath != null && !nkeySeedPath.isBlank();
    }
}
