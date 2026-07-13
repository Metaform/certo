package org.metaform.certo.protocol.ccm240.model;

/**
 * The {@code certificateStatus} values of a v2.4.0 {@code /companycertificate/status} message.
 * The v3 {@link org.metaform.certo.common.model.AcceptanceStatus} superset adds {@code ERRORED} (which
 * has no v2.4.0 equivalent and down-maps to {@code REJECTED}); v3 {@code RETRIEVED} corresponds to the
 * v2.4.0 {@code RECEIVED}.
 */
public enum Ccm240StatusValue {
    RECEIVED,
    ACCEPTED,
    REJECTED
}
