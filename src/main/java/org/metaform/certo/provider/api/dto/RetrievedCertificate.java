package org.metaform.certo.provider.api.dto;

/**
 * Internal result of a certificate retrieval: the JSON metadata and the PDF binary that the
 * controller assembles into the {@code multipart/related} response (CX-0135 &sect;4.4.3).
 */
public record RetrievedCertificate(CertificateMetadata metadata, byte[] pdf) {
}
