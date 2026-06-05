package org.metaform.certo.common.model;

import java.util.Set;

/**
 * States of the Acceptance phase of a {@code Certificate Exchange} (CX-0135 &sect;2.1.3), owned by
 * the Certificate Consumer.
 */
public enum AcceptanceStatus {
    /**
     * The consumer fetched the certificate and is processing it. Non-terminal and <em>optional</em>:
     * an exchange may transition straight from {@code FULFILLED} to a terminal acceptance state without
     * first reporting {@code RETRIEVED} (CX-0135 &sect;2.1.3).
     */
    RETRIEVED(false),
    /** The consumer accepted the certificate. Terminal. */
    ACCEPTED(true),
    /** The consumer did not accept the certificate content (a business decision). Terminal. */
    REJECTED(true),
    /** The consumer found the certificate to be in error (a business error). Terminal. */
    ERRORED(true);

    private final boolean terminal;

    AcceptanceStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Whether an {@code errors} array must accompany this status (CX-0135 &sect;4.4.4). */
    public boolean requiresErrors() {
        return this == REJECTED || this == ERRORED;
    }

    /** The Acceptance states reachable from this one (CX-0135 &sect;2.1.3 state machine). */
    public Set<AcceptanceStatus> allowedNext() {
        return switch (this) {
            case RETRIEVED -> Set.of(ACCEPTED, REJECTED, ERRORED);
            case ACCEPTED, REJECTED, ERRORED -> Set.of();
        };
    }
}
