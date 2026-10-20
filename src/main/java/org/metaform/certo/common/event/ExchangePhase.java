package org.metaform.certo.common.event;

/**
 * The phase of the the SCE specification exchange state machine a status change belongs to.
 */
public enum ExchangePhase {
    /** Provider-owned: PENDING / FULFILLED / DECLINED. */
    FULFILLMENT,
    /** Consumer-owned: ACCEPTED / REJECTED. */
    ACCEPTANCE
}
