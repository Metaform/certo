package org.metaform.certo.common.model;

/**
 * States of the {@code Certificate Lifecycle} — the publication lifecycle of a
 * certificate as an artifact, independent of the {@code Certificate Exchange}.
 * Publishing a new {@code revision} is not a lifecycle transition; the {@code revision}
 * counter carries the versioning.
 */
public enum LifecycleStatus {
    /** The certificate is published and retrievable at its latest {@code revision}. */
    AVAILABLE(false),
    /** The provider withdrew the certificate; it is no longer available. Terminal. */
    WITHDRAWN(true);

    private final boolean terminal;

    LifecycleStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
