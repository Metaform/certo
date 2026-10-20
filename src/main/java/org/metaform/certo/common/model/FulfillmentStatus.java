package org.metaform.certo.common.model;

import java.util.Set;

/**
 * States of the Fulfillment phase of a {@code Certificate Exchange}, owned by the Certificate Provider.
 * Technical/transport failures are not exchange states; the one negative outcome is {@code DECLINED},
 * whose grounds travel in the status payload's {@code errors} array.
 */
public enum FulfillmentStatus {
    /** The provider accepted the request and began preparing the certificate. */
    ACKNOWLEDGED(false),
    /** The provider submitted the request to an external certification authority and awaits issuance. */
    CERTIFICATE_REQUESTED(false),
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
            case ACKNOWLEDGED -> Set.of(CERTIFICATE_REQUESTED, FULFILLED, DECLINED);
            case CERTIFICATE_REQUESTED -> Set.of(FULFILLED, DECLINED);
            case FULFILLED, DECLINED -> Set.of();
        };
    }
}
