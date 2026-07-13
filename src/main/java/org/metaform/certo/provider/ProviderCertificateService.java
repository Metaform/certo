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
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.provider.dto.CertificateAdded;
import org.metaform.certo.provider.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.dto.CertificatePage;
import org.metaform.certo.provider.dto.CertificatePublication;
import org.metaform.certo.provider.dto.CertificateQuery;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.dto.CertificateRequestResponse;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.dto.ExchangeView;
import org.metaform.certo.provider.dto.NewCertificate;
import org.metaform.certo.provider.dto.NewRevision;
import org.metaform.certo.provider.dto.PublishRequest;
import org.metaform.certo.provider.dto.StoredDocument;
import org.metaform.certo.provider.dto.WithdrawnCertificate;
import org.metaform.certo.provider.spi.ConsumerNotifier;
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
 * Implements the Certificate Provider API behavior (CX-0135 v3): opening exchanges on request,
 * reporting fulfillment status, serving certificate metadata and document binaries, recording
 * acceptance feedback, and answering searches. State is held through the injected store adapters.
 */
@Service
public class ProviderCertificateService {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateService.class);

    /** Search fields the provider supports (CX-0135 §3.3.4); any other field is rejected with 501. */
    private static final Set<String> SUPPORTED_SEARCH_FIELDS = Set.of(
            "certificateType", "certifiedLocations.bpnl", "certifiedLocations.bpns", "certifiedLocations.bpna");

    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final ProviderCertificateStore certificates;
    private final ProviderDocumentStore documents;
    private final ProviderCertificateExchangeStore exchanges;
    private final ExchangeBindingStore bindings;
    private final CloudEventCodec codec;
    private final ProcessedEventStore processedEvents;
    private final ConsumerNotifier consumerNotifications;
    private final CertoProperties properties;
    private final Clock clock;

    public ProviderCertificateService(ProviderCertificateStore certificates, ProviderDocumentStore documents,
                                      ProviderCertificateExchangeStore exchanges, ExchangeBindingStore bindings,
                                      CloudEventCodec codec, ProcessedEventStore processedEvents,
                                      ConsumerNotifier consumerNotifications,
                                      CertoProperties properties, Clock clock) {
        this.certificates = certificates;
        this.documents = documents;
        this.exchanges = exchanges;
        this.bindings = bindings;
        this.codec = codec;
        this.processedEvents = processedEvents;
        this.consumerNotifications = consumerNotifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange} (CX-0135 &sect;3.3.1). Each request opens a
     * new exchange (so a re-attempt after a terminal outcome is a distinct exchange, &sect;2.1.1). Any
     * certificate type is accepted: a held certificate covering the requested locations &rarr; immediate
     * {@code FULFILLED}; otherwise {@code CERTIFICATION_REQUESTED} — the request is submitted to the
     * certification-authority backend and the exchange waits for it to {@link #addCertificate issue} the
     * certificate, {@link #failRequest fail}, or {@link #declineRequest decline} it.
     */
    public CertificateRequestResponse requestCertificate(CertificateRequest request) {
        if (isBlank(request.certificateType())) {
            throw ApiException.badRequest("A certificate request must specify a certificateType");
        }
        var exchangeId = newExchangeId();
        var counterparty = properties.consumer().bpn();
        var requestedLocations = request.certifiedLocations() == null ? List.<String>of() : request.certifiedLocations();

        var held = findHeldCertificate(request.certificateType(), requestedLocations);
        if (held.isPresent()) {
            var certificate = held.get();
            var revision = certificate.latestRevision().revision();
            var exchange = new ProviderCertificateExchange(
                    exchangeId, certificate.certificateId(), revision, counterparty, FulfillmentStatus.FULFILLED);
            exchange.markConsumerInitiated();
            exchanges.save(exchange);
            return new CertificateRequestResponse(
                    exchangeId, certificate.certificateId(), revision, FulfillmentStatus.FULFILLED, null);
        }

        // Not held: submit to the certification authority and wait for its response (no certificate yet).
        var exchange = ProviderCertificateExchange.pending(
                exchangeId, counterparty, request.certificateType(), requestedLocations);
        exchanges.save(exchange);
        return new CertificateRequestResponse(
                exchangeId, null, null, FulfillmentStatus.CERTIFICATION_REQUESTED, null);
    }

    /**
     * Backend outcome — the certification authority could not issue the certificate: fails a waiting
     * exchange ({@code CERTIFICATION_REQUESTED → FAILED}) and pushes the terminal status to the consumer.
     * Illegal once the exchange has reached a terminal state (409).
     */
    public CertificateRequestStatus failRequest(String exchangeId, String reason) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        var message = (reason == null || reason.isBlank())
                ? "The certification authority could not issue the certificate" : reason;
        exchange.transitionFulfillment(FulfillmentStatus.FAILED, List.of(new StatusError(message)));
        pushFulfillmentStatus(exchange);
        return toRequestStatus(exchange);
    }

    /**
     * Backend outcome — the provider declines the request (a business decision, distinct from a technical
     * {@link #failRequest failure}): a waiting exchange &rarr; {@code DECLINED}, pushing the terminal status
     * to the consumer. Illegal once the exchange has reached a terminal state (409).
     */
    public CertificateRequestStatus declineRequest(String exchangeId, String reason) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        var message = (reason == null || reason.isBlank()) ? "The provider declined the request" : reason;
        exchange.transitionFulfillment(FulfillmentStatus.DECLINED, List.of(new StatusError(message)));
        LOG.info("Fulfillment DECLINED for exchange {}: {}", exchangeId, message);
        pushFulfillmentStatus(exchange);
        return toRequestStatus(exchange);
    }

    /**
     * Stores a caller-supplied document binary (the backend uploads it ahead of the certificate that will
     * reference it) and returns its assigned id. The content is carried Base64-encoded; {@code mediaType}
     * and {@code language} default to {@code application/pdf} / {@code en}.
     */
    public StoredDocument addDocument(String mediaType, String language, String contentBase64) {
        if (contentBase64 == null || contentBase64.isBlank()) {
            throw ApiException.badRequest("A document must include contentBase64");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 is not valid Base64");
        }
        var documentId = "doc-" + UUID.randomUUID();
        var type = (mediaType == null || mediaType.isBlank()) ? PDF_MEDIA_TYPE : mediaType;
        var lang = (language == null || language.isBlank()) ? "en" : language;
        documents.save(new Document(documentId, LocalDate.now(clock), lang, type, content));
        return new StoredDocument(documentId);
    }

    /**
     * Backend outcome — the certification authority issued a certificate: adds it (with the real attributes
     * it was issued with — registration number, validity, type version, locations, issuer/validator, and the
     * documents already uploaded via {@link #addDocument}) to the provider's holdings and fulfills every
     * waiting exchange it satisfies (matching {@code certificateType} and covering the requested locations),
     * pushing a {@code FULFILLED} status to each of those consumers. The certificate is thereafter held, so
     * later requests for it are fulfilled immediately. It is always a fresh, single-revision certificate;
     * revising an existing one is {@link #addRevision}.
     */
    public CertificateAdded addCertificate(NewCertificate request) {
        if (isBlank(request.certificateType())) {
            throw ApiException.badRequest("A certificate must include a certificateType");
        }
        if (request.registrationNumber() == null || request.registrationNumber().isBlank()) {
            throw ApiException.badRequest("A certificate must include a registrationNumber");
        }
        requireValidityWindow(request.validFrom(), request.validUntil());
        validateCertifiedLocations(request.certifiedLocations());
        requireExistingDocuments(request.documentIds());
        var docIds = request.documentIds();

        var certificateId = newCertificateId();
        var certificate = new Certificate(certificateId, request.certificateType(), request.certificateTypeVersion(),
                request.registrationNumber(), request.trustLevel(), request.areaOfApplication(),
                request.certifiedLocations(), request.issuer(), request.validator());
        certificate.addRevision(new CertificateRevision(1, request.validFrom(), request.validUntil(), docIds));
        certificates.save(certificate);
        var revision = certificate.latestRevision().revision();

        var fulfilled = new ArrayList<String>();
        for (var exchange : exchanges.all()) {
            if (isWaitingFor(exchange, certificate)) {
                exchange.fulfill(certificateId, revision);
                pushFulfillmentStatus(exchange);
                fulfilled.add(exchange.exchangeId());
            }
        }
        LOG.info("Added certificate {} ({}) with {} document(s); fulfilled {} waiting exchange(s)",
                certificateId, request.certificateType(), docIds.size(), fulfilled.size());
        return new CertificateAdded(certificateId, revision, fulfilled);
    }

    /** Validates the issued certified locations: non-empty, exactly one MAIN_LOCATION, each with a bpnl and bpna. */
    private static void validateCertifiedLocations(List<CertifiedLocation> locations) {
        if (locations == null || locations.isEmpty()) {
            throw ApiException.badRequest("A certificate must include at least one certifiedLocation");
        }
        var mainCount = locations.stream().filter(l -> l.locationRole() == LocationRole.MAIN_LOCATION).count();
        if (mainCount != 1) {
            throw ApiException.badRequest("Exactly one certifiedLocation must have locationRole MAIN_LOCATION");
        }
        for (var location : locations) {
            if (location.bpnl() == null || location.bpnl().isBlank()
                    || location.bpna() == null || location.bpna().isBlank()) {
                throw ApiException.badRequest("Each certifiedLocation must have a bpnl and a bpna");
            }
        }
    }

    /** Whether a consumer-initiated exchange is still awaiting the backend and this certificate satisfies it. */
    private static boolean isWaitingFor(ProviderCertificateExchange exchange, Certificate certificate) {
        return exchange.isConsumerInitiated()
                && exchange.fulfillmentStatus() == FulfillmentStatus.CERTIFICATION_REQUESTED
                && certificate.certificateType().equals(exchange.requestedType())
                && certificate.covers(exchange.requestedLocations() == null ? List.of() : exchange.requestedLocations());
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
            return; // only push terminal outcomes
        }
        consumerNotifications.notifyFulfillment(new FulfillmentStatusData(
                exchange.exchangeId(), exchange.certificateId(), status, exchange.fulfillmentErrors()));
    }

    /**
     * Provider-initiated push: notifies <b>one explicitly-named target consumer</b> of a certificate
     * lifecycle event (CX-0135 &sect;2.1.1 / &sect;3.2.1). Everything is chosen by the caller via
     * {@link PublishRequest} — the {@code lifecycleStatus} ({@code CREATED} opens an exchange for the consumer
     * to accept; {@code MODIFIED} / {@code WITHDRAWN} are one-way, no exchange), the protocol {@code version}
     * (native v3, or a named {@code 2.4.0} consumer), whether the certificate is {@code embedded} or sent by
     * reference, and the {@code revision}. There is no provider-side "who holds this" registry: reaching
     * several consumers is several publishes. The artifact <em>state</em> change ({@link #addRevision} /
     * {@link #withdraw}) is a separate operation; this call only notifies.
     */
    public CertificatePublication publish(String certificateId, PublishRequest request) {
        var req = request != null ? request : PublishRequest.defaults();
        var lifecycle = req.lifecycleStatus();
        var version = req.protocolVersion();
        if (req.consumerUrl() == null) {
            throw ApiException.badRequest("Publishing requires the target consumer's url (consumerUrl)");
        }

        String exchangeId = null;
        int revision;
        LifecycleStatusData data;
        if (lifecycle == LifecycleStatus.WITHDRAWN) {
            var certificate = certificates.find(certificateId)
                    .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
            revision = certificate.latestRevision().revision();
            data = new LifecycleStatusData(LifecycleStatus.WITHDRAWN, null, CertificateRecord.idOnly(certificateId));
        } else {
            var certificate = requireActive(certificateId);
            var rev = resolveRevision(certificate, req.revision());
            revision = rev.revision();
            var certData = req.embedded()
                    ? toRecordWithContent(certificate, rev)
                    : CertificateRecord.lightTriage(certificateId, rev.revision(), certificate.certificateType(),
                            rev.validFrom(), rev.validUntil());
            if (lifecycle == LifecycleStatus.CREATED) {
                // A CREATED push opens an exchange (the consumer accepts it, closing the loop).
                exchangeId = newExchangeId();
                exchanges.save(new ProviderCertificateExchange(
                        exchangeId, certificateId, revision, properties.consumer().bpn(), FulfillmentStatus.FULFILLED));
            }
            data = new LifecycleStatusData(lifecycle, exchangeId, certData);
        }

        // The routing target is explicit in the request; its version selects the adapter, its callbackUrl the
        // endpoint (a native target uses the configured consumer URL).
        var target = new ExchangeBinding(exchangeId, certificateId, version, CounterpartyRole.CONSUMER,
                req.consumerBpn(), null, req.consumerUrl());
        // For a CREATED push to a non-native consumer, record the binding so its acceptance /status correlates.
        if (exchangeId != null && version != ProtocolVersion.NATIVE) {
            bindings.record(target);
        }
        var notified = consumerNotifications.notifyLifecycle(target, data);
        return new CertificatePublication(exchangeId, certificateId, revision, notified);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * State change: creates a <b>new version</b> of an existing certificate — appends a revision with the
     * caller's issued validity window and documents (uploaded first via {@link #addDocument}), lifecycle
     * {@code CREATED → MODIFIED} (CX-0135 &sect;2.2.4). The certificate's other metadata (type, locations,
     * issuer, …) is carried over. Does <b>not</b> notify any consumer — that is a separate,
     * explicitly-targeted {@link #publish} of a {@code MODIFIED} event.
     */
    public CertificateLifecycleResult addRevision(String certificateId, NewRevision request) {
        var certificate = requireActive(certificateId);
        requireValidityWindow(request.validFrom(), request.validUntil());
        requireExistingDocuments(request.documentIds());
        var revision = certificate.nextRevisionNumber();
        certificate.addRevision(new CertificateRevision(
                revision, request.validFrom(), request.validUntil(), request.documentIds()));
        return new CertificateLifecycleResult(certificateId, revision, LifecycleStatus.MODIFIED);
    }

    private static void requireValidityWindow(LocalDate validFrom, LocalDate validUntil) {
        if (validFrom == null || validUntil == null) {
            throw ApiException.badRequest("Must include validFrom and validUntil");
        }
        if (validFrom.isAfter(validUntil)) {
            throw ApiException.badRequest("validFrom must not be after validUntil");
        }
    }

    /** Validates that a certificate/revision references at least one already-uploaded document. */
    private void requireExistingDocuments(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw ApiException.badRequest(
                    "Must reference at least one document (upload it via POST /management/v1/documents first)");
        }
        for (var documentId : documentIds) {
            if (documents.find(documentId).isEmpty()) {
                throw ApiException.notFound("Unknown documentId: " + documentId);
            }
        }
    }

    /**
     * State change: withdraws a certificate (lifecycle &rarr; WITHDRAWN, terminal). A withdrawn certificate
     * is no longer retrievable as full metadata and is excluded from search; instead
     * {@code GET /certificates/{id}} returns the minimal withdrawn status body (&sect;3.3.2). Does <b>not</b>
     * notify any consumer — that is a separate, explicitly-targeted {@link #publish} of a {@code WITHDRAWN}.
     */
    public CertificateLifecycleResult withdraw(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is already withdrawn");
        }
        certificate.withdraw();
        return new CertificateLifecycleResult(certificateId, certificate.latestRevision().revision(), LifecycleStatus.WITHDRAWN);
    }

    /** Returns the provider's full view of an exchange — both phases (management/inspection; not in CX-0135). */
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
            }));
        }
        applyOnce(pending);
    }

    private void applyOnce(List<PendingEvent> pending) {
        for (var event : pending) {
            if (processedEvents.firstSeen(event.dedupKey())) {
                event.apply().run();
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

    /** Resolves a specific revision, or the latest when {@code revision <= 0} (the default/unspecified). */
    private static CertificateRevision resolveRevision(Certificate certificate, int revision) {
        return (revision <= 0)
                ? certificate.latestRevision()
                : certificate.revision(revision).orElseThrow(() ->
                        ApiException.notFound("Unknown revision " + revision + " for certificate " + certificate.certificateId()));
    }

    /** Builds the full §4 certificate record for a given revision (no document {@code contentBase64}). */
    private CertificateRecord toRecord(Certificate c, CertificateRevision rev) {
        return toRecord(c, rev, false);
    }

    /** Builds the full §4 certificate record, optionally inlining each document's binary as {@code contentBase64}. */
    private CertificateRecord toRecordWithContent(Certificate c, CertificateRevision rev) {
        return toRecord(c, rev, true);
    }

    private CertificateRecord toRecord(Certificate c, CertificateRevision rev, boolean withContent) {
        var docRefs = rev.documentIds().stream()
                .map(documents::find)
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

    private Optional<Certificate> findHeldCertificate(String certificateType, List<String> requestedLocations) {
        return certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> c.certificateType().equals(certificateType))
                .filter(c -> c.covers(requestedLocations))
                .findFirst();
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
