package org.metaform.certo.common.model;

import java.util.Set;

/**
 * States of the Fulfillment phase of a {@code Certificate Exchange}, owned by the Certificate Provider.
 * One non-terminal waiting state ({@code RECEIVED} — how the provider produces the certificate is not
 * modeled as exchange state), one hand-off ({@code FULFILLED}), one negative terminal ({@code DECLINED})
 * whose grounds travel in the status payload's {@code errors} array. Technical/transport failures are not
 * exchange states.
 */
public enum FulfillmentStatus {
    /** The provider accepted the request and is working to make the certificate available. */
    RECEIVED(false),
    /** The certificate is prepared and available for retrieval. Hand-off point to the Acceptance phase. */
    FULFILLED(false),
    /** The provider did not fulfill the request; the grounds are carried in the errors array. Terminal. */
    DECLINED(true);

    private final boolean terminal;

    FulfillmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** The Fulfillment states reachable from this one (SCE exchange state machine). */
    public Set<FulfillmentStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(FULFILLED, DECLINED);
            case FULFILLED, DECLINED -> Set.of();
        };
    }
}
