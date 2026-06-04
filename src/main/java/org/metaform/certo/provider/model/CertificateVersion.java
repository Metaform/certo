package org.metaform.certo.provider.model;

import java.time.LocalDate;

/**
 * A single published version of a certificate (CX-0135 &sect;2.2.1). The validity window is a
 * property of the version; the binary is the PDF served on retrieval.
 */
public record CertificateVersion(
        int version,
        LocalDate validFrom,
        LocalDate validUntil,
        byte[] pdf) {
}
