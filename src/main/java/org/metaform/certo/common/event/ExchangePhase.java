package org.metaform.certo.common.event;

/**
 * The phase of the CX-0135 &sect;2.1.3 exchange state machine a status change belongs to.
 */
public enum ExchangePhase {
    /** Provider-owned: REQUESTED / ACKNOWLEDGED / CERTIFICATION_REQUESTED / FULFILLED / DECLINED / FAILED. */
    FULFILLMENT,
    /** Consumer-owned: RETRIEVED / ACCEPTED / REJECTED / ERRORED. */
    ACCEPTANCE
}
