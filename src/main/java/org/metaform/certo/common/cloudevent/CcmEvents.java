package org.metaform.certo.common.cloudevent;

/**
 * CloudEvents {@code type} identifiers and {@code dataschema} URIs defined by CX-0135 for Company
 * Certificate Management. Event types follow the reverse-DNS convention of CX-0000 &sect;2.3.
 */
public final class CcmEvents {

    public static final String CONTENT_TYPE = "application/cloudevents+json";

    public static final String TYPE_LIFECYCLE_STATUS = "org.catena-x.ccm.CertificateLifecycleStatus.v1";
    public static final String TYPE_FULFILLMENT_STATUS = "org.catena-x.ccm.CertificateFulfillmentStatus.v1";
    public static final String TYPE_ACCEPTANCE_STATUS = "org.catena-x.ccm.CertificateAcceptanceStatus.v1";

    public static final String SCHEMA_LIFECYCLE_STATUS = "https://w3id.org/catenax/schemas/ccm/certificate-lifecycle-status.json";
    public static final String SCHEMA_FULFILLMENT_STATUS = "https://w3id.org/catenax/schemas/ccm/certificate-fulfillment-status.json";
    public static final String SCHEMA_ACCEPTANCE_STATUS = "https://w3id.org/catenax/schemas/ccm/certificate-acceptance-status.json";

    private CcmEvents() {
    }
}
