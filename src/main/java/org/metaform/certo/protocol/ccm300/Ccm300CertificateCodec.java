package org.metaform.certo.protocol.ccm300;

import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.protocol.ccm300.model.Ccm300Certificate;

import java.util.List;

/**
 * Maps between the version-neutral domain {@link CertificateRecord} and the CX-0135 v3 wire
 * {@link Ccm300Certificate}. Today the two shapes coincide, so this is a near-identity mapping — but it is
 * the seam through which BPC 4.0.0 (or a later version) is absorbed: when the wire shape diverges, only
 * this codec and {@link Ccm300Certificate} change; the core keeps its neutral model. Stateless.
 */
public final class Ccm300CertificateCodec {

    private Ccm300CertificateCodec() {
    }

    /** Domain &rarr; v3 wire. */
    public static Ccm300Certificate toWire(CertificateRecord record) {
        if (record == null) {
            return null;
        }
        return new Ccm300Certificate(
                record.certificateId(),
                record.revision(),
                record.certificateType(),
                record.certificateTypeVersion(),
                record.registrationNumber(),
                record.validFrom(),
                record.validUntil(),
                record.trustLevel(),
                record.areaOfApplication(),
                toWireLocations(record.certifiedLocations()),
                record.issuer() == null ? null
                        : new Ccm300Certificate.Issuer(record.issuer().issuerName(), record.issuer().issuerBpn()),
                record.validator() == null ? null
                        : new Ccm300Certificate.Validator(record.validator().validatorName(), record.validator().validatorBpn()),
                toWireDocuments(record.documents()));
    }

    /** v3 wire &rarr; domain. */
    public static CertificateRecord toDomain(Ccm300Certificate wire) {
        if (wire == null) {
            return null;
        }
        return new CertificateRecord(
                wire.certificateId(),
                wire.revision(),
                wire.certificateType(),
                wire.certificateTypeVersion(),
                wire.registrationNumber(),
                wire.validFrom(),
                wire.validUntil(),
                wire.trustLevel(),
                wire.areaOfApplication(),
                toDomainLocations(wire.certifiedLocations()),
                wire.issuer() == null ? null
                        : new CertificateIssuer(wire.issuer().issuerName(), wire.issuer().issuerBpn()),
                wire.validator() == null ? null
                        : new CertificateValidator(wire.validator().validatorName(), wire.validator().validatorBpn()),
                toDomainDocuments(wire.documents()));
    }

    private static List<Ccm300Certificate.Location> toWireLocations(List<CertifiedLocation> locations) {
        return locations == null ? null : locations.stream()
                .map(l -> new Ccm300Certificate.Location(l.bpnl(), l.bpna(), l.bpns(), l.locationRole(), l.areaOfApplication()))
                .toList();
    }

    private static List<CertifiedLocation> toDomainLocations(List<Ccm300Certificate.Location> locations) {
        return locations == null ? null : locations.stream()
                .map(l -> new CertifiedLocation(l.bpnl(), l.bpna(), l.bpns(), l.locationRole(), l.areaOfApplication()))
                .toList();
    }

    private static List<Ccm300Certificate.Document> toWireDocuments(List<CertificateDocument> documents) {
        return documents == null ? null : documents.stream()
                .map(d -> new Ccm300Certificate.Document(d.documentId(), d.createdDate(), d.language(), d.mediaType(), d.contentBase64()))
                .toList();
    }

    private static List<CertificateDocument> toDomainDocuments(List<Ccm300Certificate.Document> documents) {
        return documents == null ? null : documents.stream()
                .map(d -> new CertificateDocument(d.documentId(), d.createdDate(), d.language(), d.mediaType(), d.contentBase64()))
                .toList();
    }
}
