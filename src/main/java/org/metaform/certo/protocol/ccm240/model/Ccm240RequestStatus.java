package org.metaform.certo.protocol.ccm240.model;

/**
 * The {@code requestStatus} values of a v2.4.0 {@code /companycertificate/request} reply. The
 * v3 Fulfillment phase ({@link org.metaform.certo.common.model.FulfillmentStatus}) collapses onto these
 * three: {@code REQUESTED}/{@code ACKNOWLEDGED}/{@code CERTIFICATION_REQUESTED} &rarr; {@code IN_PROGRESS},
 * {@code FULFILLED} &rarr; {@code COMPLETED}, {@code DECLINED}/{@code FAILED} &rarr; {@code REJECTED}.
 */
public enum Ccm240RequestStatus {
    IN_PROGRESS,
    COMPLETED,
    REJECTED
}
