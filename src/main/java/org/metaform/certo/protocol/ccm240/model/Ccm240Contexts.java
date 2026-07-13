package org.metaform.certo.protocol.ccm240.model;

/**
 * The {@code header.context} discriminator values of the CX-0135 <b>v2.4.0</b> Company Certificate
 * Notification API (the v2.4.0 wire protocol this adapter bridges). Each identifies one v2.4.0 message
 * type; the adapter maps them to/from CX-0135 v3 CloudEvents.
 */
public final class Ccm240Contexts {

    /** Consumer &rarr; provider: request a certificate ({@code POST /companycertificate/request}). */
    public static final String REQUEST = "CompanyCertificateManagement-CCMAPI-Request:1.0.0";
    /** Provider &rarr; consumer: push the full certificate inline ({@code POST /companycertificate/push}). */
    public static final String PUSH = "CompanyCertificateManagement-CCMAPI-Push:1.0.0";
    /** Consumer &rarr; provider: feedback on a consumed certificate ({@code POST /companycertificate/status}). */
    public static final String STATUS = "CompanyCertificateManagement-CCMAPI-Status:1.0.0";
    /** Provider &rarr; consumer: notify a certificate is available ({@code POST /companycertificate/available}). */
    public static final String AVAILABLE = "CompanyCertificateManagement-CCMAPI-Available:1.0.0";

    private Ccm240Contexts() {
    }
}
