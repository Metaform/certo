package org.metaform.certo.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The CX-0135 wire protocol versions this runtime can speak. Each is realized by a pair of adapters
 * ({@link ProtocolNotifier} / {@link ProtocolAcceptanceReporter}) registered under it; the canonical
 * internal model is version-neutral, and {@link #NATIVE} is the version the internal model is currently
 * expressed in. Adding a future version means adding a constant here and registering its adapters — no
 * change to the core.
 *
 * <p>The <b>wire form</b> (used on the management API and to key {@link ExchangeBinding}s) is the version
 * number, e.g. {@code "2.4.0"} — not the enum name; JSON binds via {@link #wireValue()} / {@link #fromWire}.
 */
public enum ProtocolVersion {

    /** CX-0135 v2.4.0 — the message-envelope {@code /companycertificate/*} protocol. */
    CCM_2_4_0("2.4.0"),

    /** CX-0135 v3.0.0 — the CloudEvents protocol the canonical model is currently expressed in. */
    CCM_3_0_0("3.0.0");

    /** The version assumed when an exchange has no recorded binding (the counterparty is native v3). */
    public static final ProtocolVersion NATIVE = CCM_3_0_0;

    private final String wireValue;

    ProtocolVersion(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The version number as it appears on the wire / management API, e.g. {@code "2.4.0"}. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Resolves a wire version number to its constant; rejects an unknown one (yields a 400 on the API). */
    @JsonCreator
    public static ProtocolVersion fromWire(String value) {
        for (var version : values()) {
            if (version.wireValue.equals(value)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unsupported protocol version: " + value);
    }
}
