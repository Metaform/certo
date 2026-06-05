package org.metaform.certo.common.model;

import java.util.Set;

/**
 * States of the Fulfillment phase of a {@code Certificate Exchange} (CX-0135 &sect;2.1.3), owned by
 * the Certificate Provider.
 */
public enum FulfillmentStatus {
    /** The exchange was opened by the consumer; the provider has not yet acted. Internal, never reported on the wire. */
    REQUESTED(false),
    /** The provider accepted the request and began preparing the certificate. */
    ACKNOWLEDGED(false),
    /** The provider submitted the request to an external certification authority and awaits issuance. */
    CERTIFICATION_REQUESTED(false),
    /** The certificate is prepared and available for retrieval. Hand-off point to the Acceptance phase. */
    FULFILLED(false),
    /** The provider declined the request (a business decision). Terminal. */
    DECLINED(true),
    /** The provider could not produce a valid certificate (a business error). Terminal. */
    FAILED(true);

    private final boolean terminal;

    FulfillmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** The Fulfillment states reachable from this one (CX-0135 &sect;2.1.3 state machine). */
    public Set<FulfillmentStatus> allowedNext() {
        return switch (this) {
            case REQUESTED -> Set.of(ACKNOWLEDGED, DECLINED);
            case ACKNOWLEDGED -> Set.of(CERTIFICATION_REQUESTED, FULFILLED, FAILED, DECLINED);
            case CERTIFICATION_REQUESTED -> Set.of(FULFILLED, FAILED, DECLINED);
            case FULFILLED, DECLINED, FAILED -> Set.of();
        };
    }
}
