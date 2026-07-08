package org.metaform.certo.provider;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pdf.PdfGenerator;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.provider.api.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.api.dto.CertificatePage;
import org.metaform.certo.provider.api.dto.CertificatePublication;
import org.metaform.certo.provider.api.dto.CertificateQuery;
import org.metaform.certo.provider.api.dto.CertificateRequest;
import org.metaform.certo.provider.api.dto.CertificateRequestResponse;
import org.metaform.certo.provider.api.dto.CertificateRequestStatus;
import org.metaform.certo.provider.api.dto.ExchangeView;
import org.metaform.certo.provider.api.dto.WithdrawnCertificate;
import org.metaform.certo.provider.client.ConsumerNotifier;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.Document;
import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implements the Certificate Provider API behaviour (CX-0135 v3): opening exchanges on request,
 * reporting fulfillment status, serving certificate metadata and document binaries, recording
 * acceptance feedback, and answering searches. State is held in-memory (demo only).
 */
@Service
public class ProviderCertificateService {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateService.class);

    /** Certificate types this demo provider offers; requests for any other type are DECLINED. */
    private static final Set<String> OFFERED_TYPES = Set.of("ISO9001", "ISO14001", "IATF16949");

    /** Demo trigger: a request whose locations include this BPN fails during fulfillment (FAILED). */
    private static final String FAIL_LOCATION = "BPNFAIL";

    /** Search fields the provider supports (CX-0135 §3.3.4); any other field is rejected with 501. */
    private static final Set<String> SUPPORTED_SEARCH_FIELDS = Set.of(
            "certificateType", "certifiedLocations.bpnl", "certifiedLocations.bpns", "certifiedLocations.bpna");

    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final int VALIDITY_YEARS = 3;
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    /** Certificates allocated for an in-progress exchange but not yet published (until FULFILLED). */
    private final java.util.concurrent.ConcurrentMap<String, Certificate> pendingCertificates =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ProviderCertificateStore certificates;
    private final ProviderDocumentStore documents;
    private final ProviderCertificateExchangeStore exchanges;
    private final CloudEventCodec codec;
    private final ProcessedEventStore processedEvents;
    private final ConsumerNotifier consumerNotifications;
    private final CertoProperties properties;
    private final Clock clock;

    public ProviderCertificateService(ProviderCertificateStore certificates, ProviderDocumentStore documents,
                                      ProviderCertificateExchangeStore exchanges,
                                      CloudEventCodec codec, ProcessedEventStore processedEvents,
                                      ConsumerNotifier consumerNotifications,
                                      CertoProperties properties, Clock clock) {
        this.certificates = certificates;
        this.documents = documents;
        this.exchanges = exchanges;
        this.codec = codec;
        this.processedEvents = processedEvents;
        this.consumerNotifications = consumerNotifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange} (CX-0135 &sect;3.3.1). Each request opens a
     * new exchange (so a re-attempt after a terminal outcome is a distinct exchange, &sect;2.1.1):
     * unoffered type &rarr; {@code DECLINED}; a held certificate covering the requested locations &rarr;
     * immediate {@code FULFILLED}; otherwise {@code ACKNOWLEDGED} + asynchronous fulfillment.
     */
    public CertificateRequestResponse requestCertificate(CertificateRequest request) {
        var exchangeId = newExchangeId();
        var counterparty = properties.consumer().bpn();
        var requestedLocations = request.certifiedLocations() == null ? List.<String>of() : request.certifiedLocations();

        if (!OFFERED_TYPES.contains(request.certificateType())) {
            var certificateId = newCertificateId();
            var errors = List.of(new StatusError("Certificate type '" + request.certificateType() + "' is not offered"));
            var exchange = new ProviderCertificateExchange(
                    exchangeId, certificateId, 1, counterparty, FulfillmentStatus.DECLINED, errors);
            exchange.markConsumerInitiated();
            exchanges.save(exchange);
            LOG.info("Declined request for type {} (exchange {})", request.certificateType(), exchangeId);
            // A DECLINED request never yields a certificate, so certificateId/revision are omitted (CX-0135 §4.4.1).
            return new CertificateRequestResponse(exchangeId, null, null, FulfillmentStatus.DECLINED, errors);
        }

        var held = findHeldCertificate(request.certificateType(), requestedLocations);
        if (held.isPresent()) {
            var certificate = held.get();
            var revision = certificate.latestRevision().revision();
            var exchange = new ProviderCertificateExchange(
                    exchangeId, certificate.certificateId(), revision, counterparty, FulfillmentStatus.FULFILLED);
            exchange.markConsumerInitiated();
            exchanges.save(exchange);
            LOG.info("Fulfilled request for type {} -> held certificate {} r{} (exchange {})",
                    request.certificateType(), certificate.certificateId(), revision, exchangeId);
            return new CertificateRequestResponse(
                    exchangeId, certificate.certificateId(), revision, FulfillmentStatus.FULFILLED, null);
        }

        // Not held: accept and produce asynchronously. Allocate identity now; publish at FULFILLED.
        var certificateId = newCertificateId();
        pendingCertificates.put(exchangeId, buildCertificate(certificateId, request.certificateType(), requestedLocations));
        var exchange = new ProviderCertificateExchange(
                exchangeId, certificateId, 1, counterparty, FulfillmentStatus.ACKNOWLEDGED);
        exchange.markConsumerInitiated();
        exchange.planSteps(FulfillmentStatus.CERTIFICATION_REQUESTED, FulfillmentStatus.FULFILLED);
        if (requestedLocations.contains(FAIL_LOCATION)) {
            exchange.markWillFail();
        }
        exchanges.save(exchange);
        LOG.info("Acknowledged request for type {} -> certificate {} (exchange {}, async fulfillment)",
                request.certificateType(), certificateId, exchangeId);
        return new CertificateRequestResponse(
                exchangeId, certificateId, 1, FulfillmentStatus.ACKNOWLEDGED, null);
    }

    /**
     * Advances an in-progress exchange one Fulfillment step (demo trigger for asynchronous fulfillment):
     * {@code ACKNOWLEDGED → CERTIFICATION_REQUESTED → FULFILLED}, or {@code → FAILED}. On reaching
     * {@code FULFILLED} the allocated certificate (and its documents) are published.
     */
    public CertificateRequestStatus advance(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        if (!exchange.hasPendingFulfillment()) {
            return toRequestStatus(exchange);
        }
        var next = exchange.pollNextStep();
        if (next == FulfillmentStatus.FULFILLED && exchange.willFail()) {
            pendingCertificates.remove(exchangeId);
            exchange.transitionFulfillment(FulfillmentStatus.FAILED,
                    List.of(new StatusError("The certification authority rejected the certificate")));
            LOG.info("Fulfillment FAILED for exchange {}", exchangeId);
        } else if (next == FulfillmentStatus.FULFILLED) {
            var published = pendingCertificates.remove(exchangeId);
            if (published != null) {
                certificates.save(published); // publication (lifecycle CREATED)
            }
            exchange.transitionFulfillment(FulfillmentStatus.FULFILLED, null);
            LOG.info("Fulfilled exchange {} -> published certificate {}", exchangeId, exchange.certificateId());
        } else {
            exchange.transitionFulfillment(next, null);
            LOG.info("Advanced exchange {} to {}", exchangeId, next);
        }
        pushFulfillmentStatus(exchange);
        return toRequestStatus(exchange);
    }

    /**
     * Pushes the current Fulfillment status of a consumer-initiated exchange to the consumer's
     * notification API (CX-0135 &sect;3.2.1) — the push counterpart of polling. The provider MUST push at
     * least the provider-owned terminal outcomes (FULFILLED/DECLINED/FAILED).
     */
    private void pushFulfillmentStatus(ProviderCertificateExchange exchange) {
        if (!exchange.isConsumerInitiated()) {
            return;
        }
        var status = exchange.fulfillmentStatus();
        if (status != FulfillmentStatus.FULFILLED && status != FulfillmentStatus.FAILED && status != FulfillmentStatus.DECLINED) {
            return; // only push terminal outcomes in this demo
        }
        consumerNotifications.notifyFulfillment(new FulfillmentStatusData(
                exchange.exchangeId(), exchange.certificateId(), status, exchange.fulfillmentErrors()));
    }

    /**
     * Provider-initiated push (CX-0135 &sect;2.1.1): opens a {@code Certificate Exchange} for a held
     * certificate (entering directly at {@code FULFILLED}) and notifies the consumer with a
     * {@code CertificateLifecycleStatus} {@code CREATED} event carrying the light-triage subset (the
     * consumer pulls the full record + documents — push-pull).
     */
    public CertificatePublication publish(String certificateId, Integer revision) {
        var certificate = requireActive(certificateId);
        var rev = resolveRevision(certificate, revision);

        var exchangeId = newExchangeId();
        var exchange = new ProviderCertificateExchange(
                exchangeId, certificateId, rev.revision(), properties.consumer().bpn(), FulfillmentStatus.FULFILLED);
        exchanges.save(exchange);

        var data = new LifecycleStatusData(LifecycleStatus.CREATED, exchangeId,
                CertificateRecord.lightTriage(certificateId, rev.revision(), certificate.certificateType(),
                        rev.validFrom(), rev.validUntil()));
        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Published certificate {} r{} as exchange {} (consumer notified: {})",
                certificateId, rev.revision(), exchangeId, notified);
        return new CertificatePublication(exchangeId, certificateId, rev.revision(), notified);
    }

    /**
     * Publishes a new revision of an existing certificate (lifecycle CREATED &rarr; MODIFIED) and notifies
     * the consumer with a {@code MODIFIED} lifecycle event (light-triage subset). Modification does not
     * open an exchange (CX-0135 &sect;2.2.4).
     */
    public CertificateLifecycleResult modify(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is withdrawn and cannot be modified");
        }
        var today = LocalDate.now(clock);
        var revision = certificate.nextRevisionNumber();
        var validUntil = today.plusYears(VALIDITY_YEARS);
        var documentId = storeDocument(certificate, revision, today, validUntil);
        certificate.addRevision(new CertificateRevision(revision, today, validUntil, List.of(documentId)));

        var data = new LifecycleStatusData(LifecycleStatus.MODIFIED, null,
                CertificateRecord.lightTriage(certificateId, revision, certificate.certificateType(), today, validUntil));
        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Modified certificate {} -> revision {} (consumer notified: {})", certificateId, revision, notified);
        return new CertificateLifecycleResult(certificateId, revision, LifecycleStatus.MODIFIED, notified);
    }

    /**
     * Withdraws a certificate (lifecycle &rarr; WITHDRAWN, terminal) and notifies the consumer with a
     * {@code WITHDRAWN} event carrying only the {@code certificateId} (CX-0135 &sect;3.2.1). A withdrawn
     * certificate is no longer retrievable as full metadata and is excluded from search; instead
     * {@code GET /certificates/{id}} returns the minimal withdrawn status body (&sect;3.3.2).
     */
    public CertificateLifecycleResult withdraw(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is already withdrawn");
        }
        certificate.withdraw();
        var revision = certificate.latestRevision().revision();

        var data = new LifecycleStatusData(LifecycleStatus.WITHDRAWN, null, CertificateRecord.idOnly(certificateId));
        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Withdrew certificate {} (consumer notified: {})", certificateId, notified);
        return new CertificateLifecycleResult(certificateId, revision, LifecycleStatus.WITHDRAWN, notified);
    }

    /** Returns the provider's full view of an exchange — both phases (demo/inspection; not in CX-0135). */
    public ExchangeView getExchangeView(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return new ExchangeView(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.revision(),
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    /** Returns the current fulfillment status of an exchange (CX-0135 &sect;3.3.1.1). */
    public CertificateRequestStatus getRequestStatus(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return toRequestStatus(exchange);
    }

    private static CertificateRequestStatus toRequestStatus(ProviderCertificateExchange exchange) {
        // DECLINED/FAILED never yield a certificate, so certificateId/revision are omitted (CX-0135 §4.4.1/§4.4.2).
        var yieldsCertificate = exchange.fulfillmentStatus() != FulfillmentStatus.DECLINED
                && exchange.fulfillmentStatus() != FulfillmentStatus.FAILED;
        return new CertificateRequestStatus(
                exchange.exchangeId(),
                yieldsCertificate ? exchange.certificateId() : null,
                yieldsCertificate ? exchange.revision() : null,
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors());
    }

    /**
     * Retrieves certificate metadata as JSON (CX-0135 &sect;3.3.2). Always returns the latest revision as a
     * full {@link CertificateRecord} for an active certificate; for a withdrawn certificate returns the
     * minimal {@link WithdrawnCertificate} status body instead. Unknown ids 404.
     */
    public Object getCertificate(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            return WithdrawnCertificate.of(certificateId);
        }
        return toRecord(certificate, certificate.latestRevision());
    }

    /**
     * Retrieves a certificate document binary by its opaque id (CX-0135 &sect;3.3.3). Independent of any
     * certificate revision; unknown ids 404.
     */
    public Document getDocument(String documentId) {
        return documents.find(documentId)
                .orElseThrow(() -> ApiException.notFound("Unknown documentId: " + documentId));
    }

    /**
     * Ingests a certificate obtained from an external source (e.g. a legacy v2.4.0 push) into the
     * provider data plane so it becomes retrievable via {@code GET /certificates/{id}} and can be
     * published to a consumer. Stores the document binary and a single-revision certificate.
     *
     * @return the {@code certificateId} of the ingested certificate
     */
    public String ingestExternalCertificate(CertificateRecord record, Document document) {
        if (document != null) {
            documents.save(document);
        }
        var certificate = new Certificate(record.certificateId(), record.certificateType(),
                record.certificateTypeVersion(), record.registrationNumber(), record.trustLevel(),
                record.areaOfApplication(), record.certifiedLocations(), record.issuer(), record.validator());
        var documentIds = document != null ? List.of(document.documentId()) : List.<String>of();
        var revision = record.revision() != null ? record.revision() : 1;
        certificate.addRevision(new CertificateRevision(revision, record.validFrom(), record.validUntil(), documentIds));
        certificates.save(certificate);
        LOG.info("Ingested external certificate {} (revision {})", record.certificateId(), revision);
        return record.certificateId();
    }

    /**
     * Records acceptance feedback delivered as one or more {@code CertificateAcceptanceStatus}
     * CloudEvents (CX-0135 &sect;3.3.5). Acceptance MUST reference an existing exchange; an unknown
     * {@code exchangeId} is rejected with 404. The batch is atomic and duplicate events are ignored.
     */
    public void recordAcceptance(byte[] body) {
        var pending = new ArrayList<PendingEvent>();
        for (var node : codec.toEventNodes(body)) {
            var type = codec.typeOf(node);
            if (!CcmEvents.TYPE_ACCEPTANCE_STATUS.equals(type)) {
                throw ApiException.badRequest("Unexpected event type for acceptance endpoint: " + type);
            }
            var event = codec.decode(node, AcceptanceStatusData.class);
            var data = event.data();
            if (data == null || data.exchangeId() == null) {
                throw ApiException.badRequest("Acceptance event is missing data.exchangeId");
            }
            if (data.status() == null) {
                throw ApiException.badRequest("Acceptance event is missing data.status");
            }
            validateAcceptanceErrors(data.status(), data.errors());
            var exchange = exchanges.find(data.exchangeId())
                    .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + data.exchangeId()));
            if (exchange.fulfillmentStatus() != FulfillmentStatus.FULFILLED) {
                throw ApiException.conflict("Exchange " + data.exchangeId() + " is not FULFILLED"
                        + " (current fulfillment status: " + exchange.fulfillmentStatus() + ")");
            }

            pending.add(new PendingEvent(codec.dedupKey(event), () -> {
                exchange.recordAcceptance(data.status(), data.errors());
                LOG.info("Recorded acceptance {} for exchange {}", data.status(), data.exchangeId());
            }));
        }
        applyOnce(pending);
    }

    private void applyOnce(List<PendingEvent> pending) {
        for (var event : pending) {
            if (processedEvents.firstSeen(event.dedupKey())) {
                event.apply().run();
            } else {
                LOG.info("Ignoring duplicate event {}", event.dedupKey());
            }
        }
    }

    private record PendingEvent(String dedupKey, Runnable apply) {
    }

    /**
     * Searches certificates with the CX-0135 &sect;3.3.4 query grammar (a {@code $condition.$match} array of
     * field/{@code $eq} clauses combined with AND). Withdrawn certificates are excluded. Returns one page
     * of full records (latest revision, no document binaries) plus next/prev cursors for the {@code Link}
     * header. An unsupported field is rejected with 501.
     */
    public CertificatePage search(CertificateQuery query, Integer limit, String cursor) {
        var clauses = query.condition().match();
        if (clauses == null || clauses.isEmpty()) {
            throw ApiException.badRequest("Query $condition.$match must contain at least one clause");
        }
        for (var clause : clauses) {
            if (clause.field() == null || clause.eq() == null) {
                throw ApiException.badRequest("Each match clause requires $field and $eq");
            }
            if (!SUPPORTED_SEARCH_FIELDS.contains(clause.field())) {
                throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                        "Unsupported search field: " + clause.field());
            }
        }

        var matches = certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> clauses.stream().allMatch(cl -> matchesClause(c, cl.field(), cl.eq())))
                .sorted(Comparator.comparing(Certificate::certificateId))
                .map(c -> toRecord(c, c.latestRevision()))
                .toList();

        var pageLimit = (limit != null && limit > 0) ? limit : DEFAULT_SEARCH_LIMIT;
        var offset = decodeCursor(cursor);
        if (offset < 0 || offset > matches.size()) {
            throw ApiException.badRequest("Invalid pagination cursor");
        }
        var end = Math.min(offset + pageLimit, matches.size());
        var page = matches.subList(offset, end);
        var next = end < matches.size() ? encodeCursor(end) : null;
        var prev = offset > 0 ? encodeCursor(Math.max(0, offset - pageLimit)) : null;
        return new CertificatePage(page, next, prev);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static boolean matchesClause(Certificate c, String field, String value) {
        return switch (field) {
            case "certificateType" -> value.equals(c.certificateType());
            case "certifiedLocations.bpnl" -> c.certifiedLocations().stream().anyMatch(l -> value.equals(l.bpnl()));
            case "certifiedLocations.bpns" -> c.certifiedLocations().stream().anyMatch(l -> value.equals(l.bpns()));
            case "certifiedLocations.bpna" -> c.certifiedLocations().stream().anyMatch(l -> value.equals(l.bpna()));
            default -> false;
        };
    }

    private Certificate requireActive(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " has been withdrawn");
        }
        return certificate;
    }

    private static CertificateRevision resolveRevision(Certificate certificate, Integer revision) {
        return (revision == null)
                ? certificate.latestRevision()
                : certificate.revision(revision).orElseThrow(() ->
                        ApiException.notFound("Unknown revision " + revision + " for certificate " + certificate.certificateId()));
    }

    /** Builds the full §4 certificate record for a given revision (no document {@code contentBase64}). */
    private CertificateRecord toRecord(Certificate c, CertificateRevision rev) {
        var docRefs = rev.documentIds().stream()
                .map(documents::find)
                .filter(Optional::isPresent).map(Optional::get)
                .map(d -> new CertificateDocument(d.documentId(), d.createdDate(), d.language(), d.mediaType()))
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

    private Optional<Certificate> findHeldCertificate(String certificateType, List<String> requestedLocations) {
        return certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> c.certificateType().equals(certificateType))
                .filter(c -> c.covers(requestedLocations))
                .findFirst();
    }

    /** Builds (but does not publish) a new single-revision certificate covering the requested locations. */
    private Certificate buildCertificate(String certificateId, String certificateType, List<String> requestedLocations) {
        var today = LocalDate.now(clock);
        var validUntil = today.plusYears(VALIDITY_YEARS);
        var certificate = new Certificate(certificateId, certificateType, null,
                "REG-" + UUID.randomUUID().toString().substring(0, 8), "high", null,
                locationsFor(requestedLocations), null, null);
        var documentId = storeDocument(certificate, 1, today, validUntil);
        certificate.addRevision(new CertificateRevision(1, today, validUntil, List.of(documentId)));
        return certificate;
    }

    /** Synthesizes certified locations covering each requested BPN (demo data). */
    private static List<CertifiedLocation> locationsFor(List<String> requestedBpns) {
        if (requestedBpns == null || requestedBpns.isEmpty()) {
            return List.of(new CertifiedLocation("BPNL00000000HOLDER", "BPNA00000000MAIN0", null, LocationRole.MAIN_LOCATION));
        }
        var locations = new ArrayList<CertifiedLocation>();
        for (int i = 0; i < requestedBpns.size(); i++) {
            var bpn = requestedBpns.get(i);
            var role = i == 0 ? LocationRole.MAIN_LOCATION : LocationRole.ENCLOSED_LOCATION;
            var bpnl = bpn.startsWith("BPNL") ? bpn : "BPNL00000000HOLDER";
            var bpna = bpn.startsWith("BPNA") ? bpn : "BPNA00000000ADDR" + i;
            var bpns = bpn.startsWith("BPNS") ? bpn : null;
            locations.add(new CertifiedLocation(bpnl, bpna, bpns, role));
        }
        return locations;
    }

    /** Renders a PDF for a revision, stores it as a {@link Document}, and returns its opaque id. */
    private String storeDocument(Certificate certificate, int revision, LocalDate validFrom, LocalDate validUntil) {
        var documentId = "doc-" + UUID.randomUUID();
        var pdf = PdfGenerator.generate("Company Certificate: " + certificate.certificateType(), List.of(
                "Certificate ID: " + certificate.certificateId(),
                "Revision: " + revision,
                "Type: " + certificate.certificateType(),
                "Valid from: " + validFrom,
                "Valid until: " + validUntil,
                "Issued by: " + properties.provider().bpn()));
        documents.save(new Document(documentId, validFrom, "en", PDF_MEDIA_TYPE, pdf));
        return documentId;
    }

    private static void validateAcceptanceErrors(AcceptanceStatus status, List<StatusError> errors) {
        boolean hasErrors = errors != null && !errors.isEmpty();
        if (status.requiresErrors() && !hasErrors) {
            throw ApiException.badRequest("Status " + status + " requires a non-empty 'errors' array");
        }
        if (!status.requiresErrors() && hasErrors) {
            throw ApiException.badRequest("Status " + status + " must not include an 'errors' array");
        }
    }

    private static String newExchangeId() {
        return "exch-" + UUID.randomUUID();
    }

    private static String newCertificateId() {
        return "cert-" + UUID.randomUUID();
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid pagination cursor");
        }
    }
}
