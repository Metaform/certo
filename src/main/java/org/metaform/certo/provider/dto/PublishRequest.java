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
 *   <li>{@code consumerBpn} / {@code consumerDid} — the target consumer's BPN (message subject / v2.4.0
 *       receiver) and DID (the token audience). Both name the counterparty; the DID is supplied here so no
 *       component has to resolve it from the BPN.</li>
 *   <li>{@code flowId} — the live siglet flow for this push. <b>Required</b>; the token and the counterparty
 *       endpoint are resolved from the siglet cache keyed by it.</li>
 *   <li>{@code idempotencyKey} — optional. A stable key the caller reuses across retries of the same logical
 *       {@code CREATED} publish: a repeat with the same key reuses the still-live exchange (and re-notifies)
 *       rather than opening a duplicate; a new key opens a genuinely new exchange. Absent = a fresh exchange
 *       every call. Ignored for {@code MODIFIED}/{@code WITHDRAWN} (they open no exchange).</li>
 * </ul>
 * The primitives default to {@code false}/{@code 0}, the enums default in the constructor, and blank strings
 * normalize to {@code null}.
 */
public record PublishRequest(
        LifecycleStatus lifecycleStatus,
        ProtocolVersion protocolVersion,
        boolean embedded,
        int revision,
        String consumerBpn,
        String consumerDid,
        String flowId,
        String idempotencyKey) {

    public PublishRequest {
        lifecycleStatus = lifecycleStatus != null ? lifecycleStatus : LifecycleStatus.CREATED;
        protocolVersion = protocolVersion != null ? protocolVersion : ProtocolVersion.NATIVE;
        consumerBpn = blankToNull(consumerBpn);
        consumerDid = blankToNull(consumerDid);
        flowId = blankToNull(flowId);
        idempotencyKey = blankToNull(idempotencyKey);
    }

    /** The defaults used for an empty/absent publish body: {@code CREATED}, native version, by reference, latest. */
    public static PublishRequest defaults() {
        return new PublishRequest(null, null, false, 0, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
