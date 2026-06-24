package org.metaform.certo.provider.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A single revision of a certificate (CX-0135 &sect;4.2.11). The validity window is a property of the
 * revision; the documents are referenced by opaque id and their binaries live in the
 * {@code ProviderDocumentStore} (so a document can be shared across revisions).
 *
 * @param revision    the revision number (1-based, increasing)
 * @param validFrom   inclusive validity start
 * @param validUntil  inclusive validity end
 * @param documentIds opaque ids of the documents this revision references
 */
public record CertificateRevision(
        int revision,
        LocalDate validFrom,
        LocalDate validUntil,
        List<String> documentIds) {

    public CertificateRevision {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
