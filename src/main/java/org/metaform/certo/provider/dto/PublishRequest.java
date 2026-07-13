package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.protocol.ProtocolVersion;

/**
 * The body of the management "publish" call — a provider-initiated push of a certificate lifecycle event to
 * one explicitly-named target consumer (there is no provider-side "who holds this" registry; to reach
 * several consumers, publish once per consumer). The caller chooses:
 * <ul>
 *   <li>{@code lifecycleStatus} — {@code CREATED} (default; opens an exchange, the consumer accepts),
 *       {@code MODIFIED} or {@code WITHDRAWN} (one-way, no exchange). The artifact state change itself is a
 *       separate operation (revise / withdraw); this call only notifies.</li>
 *   <li>{@code protocolVersion} — {@code 3.0.0} (default; the configured native consumer) or {@code 2.4.0}
 *       (an older consumer that must be named);</li>
 *   <li>{@code embedded} — full certificate content inline ({@code true}) or by reference ({@code false},
 *       default), so the consumer pulls it (ignored for {@code WITHDRAWN});</li>
 *   <li>{@code revision} — which revision to publish; {@code 0} (default) means the latest;</li>
 *   <li>{@code consumerBpn} / {@code consumerUrl} — the target consumer's BPN and <em>base</em> URL. The
 *       {@code consumerUrl} is <b>required</b> (for every {@code protocolVersion}): it is where the push is
 *       delivered. The configured {@code certo.consumer.*} values are this runtime's own identity, not a
 *       default target.</li>
 * </ul>
 * The primitives default to {@code false}/{@code 0}, the enums default in the constructor, and blank strings
 * normalize to {@code null}. A body that omits {@code consumerUrl} is rejected.
 */
public record PublishRequest(
        LifecycleStatus lifecycleStatus,
        ProtocolVersion protocolVersion,
        boolean embedded,
        int revision,
        String consumerBpn,
        String consumerUrl) {

    public PublishRequest {
        lifecycleStatus = lifecycleStatus != null ? lifecycleStatus : LifecycleStatus.CREATED;
        protocolVersion = protocolVersion != null ? protocolVersion : ProtocolVersion.NATIVE;
        consumerBpn = blankToNull(consumerBpn);
        consumerUrl = blankToNull(consumerUrl);
    }

    /** The defaults used for an empty/absent publish body: {@code CREATED}, native version, by reference, latest. */
    public static PublishRequest defaults() {
        return new PublishRequest(null, null, false, 0, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
