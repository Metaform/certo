package org.metaform.certo.protocol.sce;

import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.protocol.sce.model.SceCertificate;

import java.util.List;

/**
 * Maps between the version-neutral domain {@link CertificateRecord} and the SCE wire
 * {@link SceCertificate}. This is the seam through which a wire change is absorbed: when the wire shape
 * diverges, only this codec and {@link SceCertificate} change; the core keeps its neutral model.
 * Stateless.
 */
public final class SceCertificateCodec {

    private SceCertificateCodec() {
    }

    /** Domain &rarr; SCE wire. */
    public static SceCertificate toWire(CertificateRecord record) {
        if (record == null) {
            return null;
        }
        return new SceCertificate(
                record.certificateId(),
                record.revision(),
                record.certificateType(),
                record.certificateTypeVersion(),
                record.registrationNumber(),
                record.validFrom(),
                record.validUntil(),
                record.areaOfApplication(),
                toWireLocations(record.certifiedLocations()),
                record.issuer() == null ? null
                        : new SceCertificate.Issuer(record.issuer().issuerName(), record.issuer().issuerId()),
                record.validator() == null ? null
                        : new SceCertificate.Validator(record.validator().validatorName(), record.validator().validatorId()),
                toWireDocuments(record.documents()));
    }

    /** SCE wire &rarr; domain. */
    public static CertificateRecord toDomain(SceCertificate wire) {
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
                wire.areaOfApplication(),
                toDomainLocations(wire.certifiedLocations()),
                wire.issuer() == null ? null
                        : new CertificateIssuer(wire.issuer().issuerName(), wire.issuer().issuerId()),
                wire.validator() == null ? null
                        : new CertificateValidator(wire.validator().validatorName(), wire.validator().validatorId()),
                toDomainDocuments(wire.documents()));
    }

    private static List<SceCertificate.Location> toWireLocations(List<CertifiedLocation> locations) {
        return locations == null ? null : locations.stream()
                .map(l -> new SceCertificate.Location(l.legalEntityId(), l.siteId(), l.addressId(), l.locationRole(), l.areaOfApplication()))
                .toList();
    }

    private static List<CertifiedLocation> toDomainLocations(List<SceCertificate.Location> locations) {
        return locations == null ? null : locations.stream()
                .map(l -> new CertifiedLocation(l.legalEntityId(), l.siteId(), l.addressId(), l.locationRole(), l.areaOfApplication()))
                .toList();
    }

    private static List<SceCertificate.Document> toWireDocuments(List<CertificateDocument> documents) {
        return documents == null ? null : documents.stream()
                .map(d -> new SceCertificate.Document(d.documentId(), d.createdDate(), d.language(), d.mediaType()))
                .toList();
    }

    private static List<CertificateDocument> toDomainDocuments(List<SceCertificate.Document> documents) {
        return documents == null ? null : documents.stream()
                .map(d -> new CertificateDocument(d.documentId(), d.createdDate(), d.language(), d.mediaType()))
                .toList();
    }
}
