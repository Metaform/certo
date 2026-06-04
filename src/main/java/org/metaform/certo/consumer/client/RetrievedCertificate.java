package org.metaform.certo.consumer.client;

/**
 * A certificate retrieved from a provider: its JSON metadata and the PDF binary (CX-0135 &sect;4.4.3).
 */
public record RetrievedCertificate(CertificateMetadata metadata, byte[] pdf) {
}
