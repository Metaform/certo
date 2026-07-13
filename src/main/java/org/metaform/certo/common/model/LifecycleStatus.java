package org.metaform.certo.common.model;

/**
 * States of the {@code Certificate Lifecycle} (CX-0135 &sect;2.2.2) — the publication lifecycle of a
 * certificate as an artifact, independent of the {@code Certificate Exchange}.
 */
public enum LifecycleStatus {
    /** First published under a new {@code certificateId}, establishing its initial {@code revision}. */
    CREATED(false),
    /** A new {@code revision} was published under the same {@code certificateId}. May recur. */
    MODIFIED(false),
    /** The provider withdrew the certificate; it is no longer available. Terminal. */
    WITHDRAWN(true);

    private final boolean terminal;

    LifecycleStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** Only {@code CREATED} opens a {@code Certificate Exchange} (CX-0135 &sect;2.2.4). */
    public boolean opensExchange() {
        return this == CREATED;
    }
}
