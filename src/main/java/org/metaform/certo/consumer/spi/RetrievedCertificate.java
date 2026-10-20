package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.model.CertificateRecord;

import java.util.List;

/**
 * A certificate the consumer pulled from a provider: its JSON metadata
 * record and the document binaries it referenced, each fetched separately via {@code GET /documents/{id}}.
 */
public record RetrievedCertificate(CertificateRecord metadata, List<RetrievedDocument> documents) {
}
