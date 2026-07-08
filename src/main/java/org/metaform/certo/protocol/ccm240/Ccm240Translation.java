package org.metaform.certo.protocol.ccm240;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.protocol.ccm240.model.BusinessPartnerCertificate31;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240StatusValue;
import org.metaform.certo.provider.model.Document;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Stateless translation between the CX-0135 <b>v2.4.0</b> (legacy) wire protocol and certo's v3 domain
 * model. This is the pure core of the backward-compatibility adapter (Phase 1): status-vocabulary
 * mapping, identity, and the {@code BusinessPartnerCertificate} 3.1.0 &harr; v3
 * {@link CertificateRecord} (+ {@link Document}) semantic-model conversion. No I/O, no Spring, no state.
 *
 * <p>Identity note: a legacy {@code documentId} identifies the certificate as a whole and maps to the v3
 * {@code certificateId}; retrieval is always latest-revision, so no revision is carried across the
 * boundary (see the migration notes under {@code certo-spec/migration}).
 */
public final class Ccm240Translation {

    private Ccm240Translation() {
    }

    // --- status vocabulary -------------------------------------------------------------------------

    /** Maps a v3 Fulfillment status onto the legacy {@code /request} reply status. */
    public static Ccm240RequestStatus toReplyStatus(FulfillmentStatus status) {
        return switch (status) {
            case FULFILLED -> Ccm240RequestStatus.COMPLETED;
            case DECLINED, FAILED -> Ccm240RequestStatus.REJECTED;
            case REQUESTED, ACKNOWLEDGED, CERTIFICATION_REQUESTED -> Ccm240RequestStatus.IN_PROGRESS;
        };
    }

    /** Maps an inbound legacy {@code certificateStatus} onto a v3 Acceptance status. */
    public static AcceptanceStatus toAcceptanceStatus(Ccm240StatusValue value) {
        return switch (value) {
            case RECEIVED -> AcceptanceStatus.RETRIEVED;
            case ACCEPTED -> AcceptanceStatus.ACCEPTED;
            case REJECTED -> AcceptanceStatus.REJECTED;
        };
    }

    /**
     * Maps a v3 Acceptance status onto the legacy {@code certificateStatus}. The v3-only {@code ERRORED}
     * has no legacy equivalent and down-maps to {@code REJECTED} (the error detail is preserved in the
     * message's {@code certificateErrors}).
     */
    public static Ccm240StatusValue toCcm240StatusValue(AcceptanceStatus status) {
        return switch (status) {
            case RETRIEVED -> Ccm240StatusValue.RECEIVED;
            case ACCEPTED -> Ccm240StatusValue.ACCEPTED;
            case REJECTED, ERRORED -> Ccm240StatusValue.REJECTED;
        };
    }

    // --- semantic model 3.1.0 <-> v3 ---------------------------------------------------------------

    /** The result of up-converting a 3.1.0 push: the v3 metadata record plus the split-out document binary. */
    public record UpConverted(CertificateRecord record, Document document) {
    }

    /**
     * Up-converts an inbound 3.1.0 push certificate to the v3 model, assigning the given
     * {@code certificateId}/{@code revision} (3.1.0 has neither) and splitting the inline document out
     * into a separate {@link Document} referenced from {@code record.documents()}.
     */
    public static UpConverted upConvert(BusinessPartnerCertificate31 cert, String certificateId, int revision) {
        Document document = null;
        List<CertificateDocument> documentRefs = null;
        if (cert.document() != null) {
            var d = cert.document();
            var content = d.contentBase64() == null ? new byte[0] : Base64.getDecoder().decode(d.contentBase64());
            var created = parseTimestamp(d.creationDate());
            var documentId = d.documentId() != null ? d.documentId() : "doc-" + certificateId + "-r" + revision;
            document = new Document(documentId, created, null, d.contentType(), content);
            documentRefs = List.of(new CertificateDocument(documentId, created, null, d.contentType()));
        }

        var locations = new ArrayList<CertifiedLocation>();
        locations.add(new CertifiedLocation(cert.businessPartnerNumber(), null, null,
                LocationRole.MAIN_LOCATION, cert.areaOfApplication()));
        if (cert.enclosedSites() != null) {
            for (var site : cert.enclosedSites()) {
                var bpn = site.enclosedSiteBpn();
                var bpns = bpn != null && bpn.startsWith("BPNS") ? bpn : null;
                var bpna = bpn != null && bpn.startsWith("BPNA") ? bpn : null;
                locations.add(new CertifiedLocation(cert.businessPartnerNumber(), bpna, bpns,
                        LocationRole.ENCLOSED_LOCATION, site.areaOfApplication()));
            }
        }

        var type = cert.type();
        var record = new CertificateRecord(
                certificateId,
                revision,
                type == null ? null : type.certificateType(),
                type == null ? null : type.certificateVersion(),
                cert.registrationNumber(),
                parseDate(cert.validFrom()),
                parseDate(cert.validUntil()),
                cert.trustLevel(),
                cert.areaOfApplication(),
                locations,
                cert.issuer() == null ? null : new CertificateIssuer(cert.issuer().issuerName(), cert.issuer().issuerBpn()),
                cert.validator() == null ? null : new CertificateValidator(cert.validator().validatorName(), cert.validator().validatorBpn()),
                documentRefs);
        return new UpConverted(record, document);
    }

    /**
     * Down-converts a v3 metadata record plus one document binary into a 3.1.0 push certificate. The
     * holder BPNL is taken from the {@code MAIN_LOCATION}; remaining locations become {@code enclosedSites}.
     * Fields with no 3.1.0 home (revision, uploader, secondary documents) are dropped.
     */
    public static BusinessPartnerCertificate31 downConvert(CertificateRecord record, Document document) {
        var locations = record.certifiedLocations() == null ? List.<CertifiedLocation>of() : record.certifiedLocations();
        var main = locations.stream()
                .filter(l -> l.locationRole() == LocationRole.MAIN_LOCATION)
                .findFirst()
                .orElse(locations.isEmpty() ? null : locations.get(0));

        var enclosedSites = locations.stream()
                .filter(l -> l != main)
                .map(l -> new BusinessPartnerCertificate31.EnclosedSite(
                        l.bpns() != null ? l.bpns() : l.bpna(), l.areaOfApplication()))
                .toList();

        BusinessPartnerCertificate31.Document doc = null;
        if (document != null) {
            doc = new BusinessPartnerCertificate31.Document(
                    document.createdDate() == null ? null
                            : document.createdDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().toString(),
                    document.documentId(),
                    document.mediaType(),
                    document.content() == null ? null : Base64.getEncoder().encodeToString(document.content()));
        }

        return new BusinessPartnerCertificate31(
                main == null ? null : main.bpnl(),
                new BusinessPartnerCertificate31.Type(record.certificateType(), record.certificateTypeVersion()),
                record.registrationNumber(),
                record.areaOfApplication() != null ? record.areaOfApplication()
                        : (main == null ? null : main.areaOfApplication()),
                enclosedSites.isEmpty() ? null : enclosedSites,
                record.validFrom() == null ? null : record.validFrom().toString(),
                record.validUntil() == null ? null : record.validUntil().toString(),
                record.issuer() == null ? null
                        : new BusinessPartnerCertificate31.Issuer(record.issuer().issuerName(), record.issuer().issuerBpn()),
                record.trustLevel(),
                record.validator() == null ? null
                        : new BusinessPartnerCertificate31.Validator(record.validator().validatorName(), record.validator().validatorBpn()),
                null,
                doc);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Parses a 3.1.0 {@code Date} ({@code yyyy-MM-dd}); tolerates a longer date-time by truncating. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
    }

    /** Parses a 3.1.0 {@code Timestamp} (date-time) to a {@link LocalDate}; falls back to a plain date. */
    private static LocalDate parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (RuntimeException e) {
            return parseDate(value);
        }
    }
}
