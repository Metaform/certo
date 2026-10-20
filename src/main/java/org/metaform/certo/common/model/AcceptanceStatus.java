package org.metaform.certo.common.model;

import java.util.Set;

/**
 * States of the Acceptance phase of a {@code Certificate Exchange}, owned by the Certificate Consumer.
 * Both states are terminal and reached directly from {@code FULFILLED}; no delivery receipt is modeled
 * (the provider observes retrieval itself), and acceptance feedback is optional — an exchange without it
 * rests at {@code FULFILLED} indefinitely.
 */
public enum AcceptanceStatus {
    /** The consumer accepted the certificate. Terminal. */
    ACCEPTED(true),
    /** The consumer did not accept the certificate; the grounds are carried in the errors array. Terminal. */
    REJECTED(true);

    private final boolean terminal;

    AcceptanceStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Whether an {@code errors} array must accompany this status. */
    public boolean requiresErrors() {
        return this == REJECTED;
    }

    /** The Acceptance states reachable from this one: both are terminal, so none. */
    public Set<AcceptanceStatus> allowedNext() {
        return Set.of();
    }
}
