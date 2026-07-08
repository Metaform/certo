package org.metaform.certo.protocol;

/**
 * Identifiers for the CX-0135 wire protocol versions this runtime can speak. Each version is realized by
 * a pair of adapters ({@link ProtocolNotifier} / {@link ProtocolAcceptanceReporter}) registered under its
 * id; the canonical internal model is version-neutral, and {@link #NATIVE} is simply the version the
 * internal model is currently expressed in. Adding a future version means registering new adapters under
 * a new id here — no change to the core.
 */
public final class ProtocolVersions {

    /** CX-0135 v2.4.0 — the message-envelope {@code /companycertificate/*} protocol. */
    public static final String CCM_2_4_0 = "2.4.0";

    /** CX-0135 v3.0.0 — the CloudEvents protocol the canonical model is currently expressed in. */
    public static final String CCM_3_0_0 = "3.0.0";

    /** The version used when an exchange has no recorded binding (i.e. the counterparty is native v3). */
    public static final String NATIVE = CCM_3_0_0;

    private ProtocolVersions() {
    }
}
