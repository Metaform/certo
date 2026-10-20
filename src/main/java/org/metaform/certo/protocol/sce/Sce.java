package org.metaform.certo.protocol.sce;

/**
 * The SCE adapter's shared wire identity: the open protocol id its components register under and the event
 * type identifiers it speaks (core-messaging reverse-DNS convention, {@code org.dsaf} domain). A plain
 * constants namespace — components reference it qualified ({@code Sce.PROTOCOL_ID}) rather than
 * implementing it, so the constants never leak into a component's own type. These are wire constants of
 * this protocol adapter; the core never names a protocol or an event type.
 */
public interface Sce {

    String PROTOCOL_ID = "sce-1.0";

    String TYPE_LIFECYCLE_STATUS = "org.dsaf.sce.CertificateLifecycleStatus.v1";
    String TYPE_FULFILLMENT_STATUS = "org.dsaf.sce.CertificateFulfillmentStatus.v1";
    String TYPE_ACCEPTANCE_STATUS = "org.dsaf.sce.CertificateAcceptanceStatus.v1";
}
