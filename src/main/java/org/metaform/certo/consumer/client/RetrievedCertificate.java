package org.metaform.certo.consumer.client;

import org.metaform.certo.common.model.CertificateRecord;

import java.util.List;

/**
 * A certificate the consumer pulled from a provider (CX-0135 &sect;3.3.2 / &sect;3.3.3): its JSON metadata
 * record and the document binaries it referenced, each fetched separately via {@code GET /documents/{id}}.
 */
public record RetrievedCertificate(CertificateRecord metadata, List<RetrievedDocument> documents) {
}
