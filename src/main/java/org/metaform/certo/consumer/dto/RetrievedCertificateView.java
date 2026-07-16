package org.metaform.certo.consumer.dto;

import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.consumer.spi.RetrievedCertificate;

import java.util.Base64;
import java.util.List;

/**
 * The certificate a management-driven {@code retrieve} pulled from the provider, returned so a client can
 * inspect it (metadata + each document's content, Base64-encoded) before deciding acceptance.
 */
public record RetrievedCertificateView(CertificateRecord certificate, List<Document> documents) {

    public record Document(String documentId, String mediaType, String contentBase64) {
    }

    public static RetrievedCertificateView of(RetrievedCertificate retrieved) {
        var documents = retrieved.documents().stream()
                .map(d -> new Document(d.documentId(), d.mediaType(),
                        d.content() == null ? null : Base64.getEncoder().encodeToString(d.content())))
                .toList();
        return new RetrievedCertificateView(retrieved.metadata(), documents);
    }
}
