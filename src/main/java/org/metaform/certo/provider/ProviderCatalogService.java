package org.metaform.certo.provider;

import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.common.web.Cursor;
import org.metaform.certo.provider.dto.CertificateAdded;
import org.metaform.certo.provider.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.dto.CertificatePage;
import org.metaform.certo.provider.dto.CertificateQuery;
import org.metaform.certo.provider.dto.NewCertificate;
import org.metaform.certo.provider.dto.NewRevision;
import org.metaform.certo.provider.dto.StoredDocument;
import org.metaform.certo.provider.dto.WithdrawnCertificate;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.Document;
import org.metaform.certo.provider.store.CertificateSpecifications;
import org.metaform.certo.provider.store.OffsetLimitPageable;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.UUID.randomUUID;
import static org.metaform.certo.common.model.LifecycleStatus.MODIFIED;
import static org.metaform.certo.common.model.LifecycleStatus.WITHDRAWN;
import static org.metaform.certo.common.web.ApiException.badRequest;
import static org.metaform.certo.common.web.ApiException.notFound;
import static org.metaform.certo.common.web.ApiException.requireText;

/**
 * The provider's <b>certificate catalog</b>: the artifacts (certificates + their revisions) and document
 * binaries the provider holds — creating, revising, withdrawing, retrieving, and searching them. The
 * exchange lifecycle (requests, fulfillment, acceptance) lives in {@link ProviderExchangeService}, which
 * consults this catalog for held certificates.
 */
@Service
@Transactional
public class ProviderCatalogService {
    /**
     * Search fields the provider supports (CX-0135 §3.3.4); any other field is rejected with 501.
     */
    private static final Set<String> SUPPORTED_SEARCH_FIELDS = Set.of(
            "certificateType", "certifiedLocations.bpnl", "certifiedLocations.bpns", "certifiedLocations.bpna");

    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final ProviderCertificateStore certificateStore;
    private final ProviderDocumentStore documentStore;
    private final ParticipantContextStore contextStore;
    private final Clock clock;

    public ProviderCatalogService(ProviderCertificateStore certificateStore,
                                  ProviderDocumentStore documentStore,
                                  ParticipantContextStore contextStore,
                                  Clock clock) {
        this.certificateStore = certificateStore;
        this.documentStore = documentStore;
        this.contextStore = contextStore;
        this.clock = clock;
    }

    /**
     * Stores a caller-supplied document binary (the backend uploads it ahead of the certificate that will
     * reference it) and returns its assigned id. The content is carried Base64-encoded; {@code mediaType}
     * and {@code language} default to {@code application/pdf} / {@code en}.
     */
    public StoredDocument addDocument(String contextId, String mediaType, String language, String contentBase64) {
        requireText(contentBase64, "A document must include contentBase64");
        checkContext(contextId);
        byte[] content;
        try {
            content = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw badRequest("Document content is not Base64");
        }
        var documentId = randomUUID().toString();
        var type = (mediaType == null || mediaType.isBlank()) ? PDF_MEDIA_TYPE : mediaType;
        var lang = (language == null || language.isBlank()) ? "en" : language;
        documentStore.save(new Document(documentId, contextId, LocalDate.now(clock), lang, type, content));
        return new StoredDocument(documentId);
    }

    /**
     * Backend outcome — the certification authority issued a certificate: adds it (with the real attributes
     * it was issued with — registration number, validity, type version, locations, issuer/validator, and the
     * documents already uploaded via {@link #addDocument}) to the provider's holdings. This is a <b>state
     * change only</b>: it does not notify anyone. Waiting consumer exchanges the certificate covers are
     * discovered via {@code fulfillableRequests} and notified one at a time. It is always a fresh,
     * single-revision certificate; revising an existing one is {@link #addRevision}.
     */
    public CertificateAdded addCertificate(String contextId, NewCertificate request) {
        requireText(request.certificateType(), "A certificate must include a certificateType");
        requireText(request.registrationNumber(), "A certificate must include a registrationNumber");
        checkValidityWindow(request.validFrom(), request.validUntil());
        validateCertifiedLocations(request.certifiedLocations());
        checkContext(contextId);
        checkExistingDocuments(contextId, request.documentIds());
        var docIds = request.documentIds();

        var certificateId = randomUUID().toString();
        var certificate = new Certificate(certificateId,
                contextId,
                request.certificateType(),
                request.certificateTypeVersion(),
                request.registrationNumber(),
                request.trustLevel(),
                request.areaOfApplication(),
                request.certifiedLocations(),
                request.issuer(),
                request.validator());
        certificate.addRevision(new CertificateRevision(1, request.validFrom(), request.validUntil(), docIds));
        certificateStore.save(certificate);
        return new CertificateAdded(certificateId, certificate.latestRevision().revision());
    }

    /**
     * State change: creates a <b>new version</b> of an existing certificate — appends a revision with the
     * caller's issued validity window and documents, lifecycle {@code CREATED → MODIFIED} (CX-0135
     * &sect;2.2.4). Does <b>not</b> notify any consumer — that is a separate, explicitly-targeted publish.
     */
    public CertificateLifecycleResult addRevision(String contextId, String certificateId, NewRevision request) {
        var certificate = resolveActiveCertificate(contextId, certificateId);
        checkValidityWindow(request.validFrom(), request.validUntil());
        checkExistingDocuments(certificate.participantContextId(), request.documentIds());
        var revision = certificate.nextRevisionNumber();
        certificate.addRevision(new CertificateRevision(revision,
                request.validFrom(),
                request.validUntil(),
                request.documentIds()));
        certificateStore.save(certificate);
        return new CertificateLifecycleResult(certificateId, revision, MODIFIED);
    }

    /**
     * State change: withdraws a certificate (lifecycle &rarr; WITHDRAWN, terminal). Excluded from search and
     * no longer retrievable as full metadata. Does <b>not</b> notify any consumer.
     */
    public CertificateLifecycleResult withdraw(String contextId, String certificateId) {
        var certificate = resolveCertificate(contextId, certificateId);
        if (certificate.lifecycleStatus() == WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is already withdrawn");
        }
        certificate.withdraw();
        certificateStore.save(certificate);
        return new CertificateLifecycleResult(certificateId, certificate.latestRevision().revision(), WITHDRAWN);
    }

    /**
     * Retrieves certificate metadata as JSON (CX-0135 &sect;3.3.2): the latest revision as a full record for
     * an active certificate, or the minimal {@link WithdrawnCertificate} status body for a withdrawn one.
     */
    @Transactional(readOnly = true)
    public Object getCertificate(String contextId, String certificateId) {
        var certificate = findCertificate(contextId, certificateId)
                .orElseThrow(() -> notFound("Unknown certificate: " + certificateId));
        if (certificate.lifecycleStatus() == WITHDRAWN) {
            return WithdrawnCertificate.of(certificateId);
        }
        return toRecord(certificate, certificate.latestRevision());
    }

    /**
     * Retrieves a certificate document binary by its opaque id (CX-0135 &sect;3.3.3), scoped to the caller's
     * tenant. Unknown id — or one owned by another tenant — is 404 (existence is not revealed across tenants).
     */
    @Transactional(readOnly = true)
    public Document getDocument(String documentId, String contextId) {
        return documentStore.find(documentId)
                .filter(d -> d.participantContextId().equals(contextId))
                .orElseThrow(() -> notFound("Unknown document: " + documentId));
    }

    /**
     * Searches certificates with the CX-0135 &sect;3.3.4 query grammar (a {@code $condition.$match} array of
     * field/{@code $eq} clauses combined with AND). Withdrawn certificates are excluded. Returns one page of
     * full records plus next/prev cursors. An unsupported field is rejected with 501.
     */
    @Transactional(readOnly = true)
    public CertificatePage search(String contextId, CertificateQuery query, Integer limit, String cursor) {
        var clauses = query.condition().match();
        if (clauses == null || clauses.isEmpty()) {
            throw badRequest("Query $condition.$match must contain at least one clause");
        }
        for (var clause : clauses) {
            if (clause.field() == null || clause.eq() == null) {
                throw badRequest("Each match clause requires $field and $eq");
            }
            if (!SUPPORTED_SEARCH_FIELDS.contains(clause.field())) {
                throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "Unsupported search field: " + clause.field());
            }
        }

        var pageLimit = (limit != null && limit > 0) ? limit : DEFAULT_SEARCH_LIMIT;
        var offset = Cursor.decode(cursor);
        if (offset < 0) {
            throw badRequest("Invalid pagination cursor");
        }
        var spec = CertificateSpecifications.matchingSearch(contextId, clauses);
        var page = certificateStore.findAll(spec, new OffsetLimitPageable(offset, pageLimit, Sort.by("certificateId")));
        if (offset > page.getTotalElements()) {
            throw badRequest("Invalid pagination cursor");
        }
        var records = page.getContent().stream()
                .map(c -> toRecord(c, c.latestRevision()))
                .toList();
        var end = offset + records.size();
        var next = end < page.getTotalElements() ? Cursor.encode(end) : null;
        var prev = offset > 0 ? Cursor.encode(Math.max(0, offset - pageLimit)) : null;
        return new CertificatePage(records, next, prev);
    }

    /**
     * The provider's non-withdrawn certificate of {@code contextId} that covers {@code requestedLocations}
     * (server-side coverage match), if any — the basis for immediate fulfillment.
     */
    public Optional<Certificate> findCertificateForLocations(String contextId,
                                                             String certificateType,
                                                             List<String> requestedLocations) {
        var spec = CertificateSpecifications.heldCovering(contextId, certificateType, requestedLocations);
        return Optional.ofNullable(certificateStore.findBy(spec, q -> q.sortBy(Sort.by("certificateId")).firstValue()));
    }

    /**
     * A certificate that must exist within the tenant's scope, else 404.
     */
    public Certificate resolveCertificate(String contextId, String certificateId) {
        return findCertificate(contextId, certificateId)
                .orElseThrow(() -> notFound("Unknown certificateId: " + certificateId));
    }

    /**
     * Maps a certificate + revision to the CX-0135 &sect;4 wire {@link CertificateRecord}, resolving the
     * revision's document descriptors from this catalog's document store. Lives here (rather than in a
     * separate mapper) because the catalog owns the documents. {@link #toRecordWithContent} additionally
     * inlines each document's binary as {@code contentBase64} (for an embedded publish).
     */
    public CertificateRecord toRecord(Certificate certificate, CertificateRevision revision) {
        return toRecord(certificate, revision, false);
    }

    public CertificateRecord toRecordWithContent(Certificate certificate, CertificateRevision revision) {
        return toRecord(certificate, revision, true);
    }

    private CertificateRecord toRecord(Certificate c, CertificateRevision rev, boolean withContent) {
        var docRefs = rev.documentIds().stream()
                .map(documentStore::find)
                .filter(Optional::isPresent).map(Optional::get)
                .map(d -> new CertificateDocument(d.documentId(), d.createdDate(), d.language(), d.mediaType(),
                        withContent ? Base64.getEncoder().encodeToString(d.content()) : null))
                .toList();
        return new CertificateRecord(
                c.certificateId(),
                rev.revision(),
                c.certificateType(),
                c.certificateTypeVersion(),
                c.registrationNumber(),
                rev.validFrom(),
                rev.validUntil(),
                c.trustLevel(),
                c.areaOfApplication(),
                c.certifiedLocations(),
                c.issuer(),
                c.validator(),
                docRefs.isEmpty() ? null : docRefs);
    }

    private Optional<Certificate> findCertificate(String contextId, String certificateId) {
        return certificateStore.find(certificateId)
                .filter(c -> contextId.equals(c.participantContextId()));
    }

    private Certificate resolveActiveCertificate(String contextId, String certificateId) {
        var certificate = resolveCertificate(contextId, certificateId);
        if (certificate.lifecycleStatus() == WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " has been withdrawn");
        }
        return certificate;
    }

    /**
     * Fails the request when the named tenant does not exist (an existence check that does not load it).
     */
    private void checkContext(String contextId) {
        requireText(contextId, "A contextId is required");
        if (!contextStore.exists(contextId)) {
            throw badRequest("Unknown participantContextId: " + contextId);
        }
    }

    /**
     * Validates the issued certified locations: non-empty, exactly one MAIN_LOCATION, each with a bpnl and bpna.
     */
    private static void validateCertifiedLocations(List<CertifiedLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            throw badRequest("A certificate must include at least one certifiedLocation");
        }
        var mainCount = locations.stream().filter(l -> l.locationRole() == LocationRole.MAIN_LOCATION).count();
        if (mainCount != 1) {
            throw badRequest("Exactly one certifiedLocation must have locationRole MAIN_LOCATION");
        }
        for (var location : locations) {
            requireText(location.bpnl(), "Each certifiedLocation must have a BPNL and a BPNA");
            requireText(location.bpna(), "Each certifiedLocation must have a BPNL and a BPNA");
        }
    }

    private static void checkValidityWindow(LocalDate validFrom, LocalDate validUntil) {
        if (validFrom == null || validUntil == null) {
            throw badRequest("Must include validFrom and validUntil");
        }
        if (validFrom.isAfter(validUntil)) {
            throw badRequest("validFrom must not be after validUntil");
        }
    }

    /**
     * Validates that a certificate/revision references at least one already-uploaded document, and that every
     * referenced document belongs to the same tenant.
     */
    private void checkExistingDocuments(String contextId, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw badRequest(
                    "Must reference at least one document (upload it via POST /management/v1/documents first)");
        }
        for (var documentId : documentIds) {
            var owned = documentStore.find(documentId)
                    .filter(d -> d.participantContextId().equals(contextId))
                    .isPresent();
            if (!owned) {
                throw notFound("Unknown documentId: " + documentId);
            }
        }
    }

}
