package org.metaform.certo.provider;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pdf.PdfGenerator;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.provider.api.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.api.dto.CertificateMetadata;
import org.metaform.certo.provider.api.dto.CertificatePage;
import org.metaform.certo.provider.api.dto.CertificatePublication;
import org.metaform.certo.provider.api.dto.CertificateQuery;
import org.metaform.certo.provider.api.dto.CertificateQueryResponse;
import org.metaform.certo.provider.api.dto.CertificateRequest;
import org.metaform.certo.provider.api.dto.CertificateRequestResponse;
import org.metaform.certo.provider.api.dto.CertificateRequestStatus;
import org.metaform.certo.provider.api.dto.ExchangeView;
import org.metaform.certo.provider.api.dto.RetrievedCertificate;
import org.metaform.certo.provider.client.ConsumerNotificationClient;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.metaform.certo.provider.model.CertificateVersion;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Implements the Certificate Provider API behaviour (CX-0135 &sect;4.4): opening exchanges on
 * request, reporting fulfillment status, serving certificate data, recording acceptance feedback,
 * and answering queries. State is held in-memory (demo only).
 */
@Service
public class ProviderCertificateService {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateService.class);

    /** Certificate types this demo provider offers; requests for any other type are DECLINED. */
    private static final Set<String> OFFERED_TYPES = Set.of("ISO9001", "ISO14001", "IATF16949");

    /** Demo trigger: a request whose locations include this BPN fails during fulfillment (FAILED). */
    private static final String FAIL_LOCATION = "BPNFAIL";

    private static final int DEFAULT_QUERY_LIMIT = 50;
    private static final int VALIDITY_YEARS = 3;

    /** Certificates allocated for an in-progress exchange but not yet published (until FULFILLED). */
    private final java.util.concurrent.ConcurrentMap<String, Certificate> pendingCertificates =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ProviderCertificateStore certificates;
    private final ProviderCertificateExchangeStore exchanges;
    private final CloudEventCodec codec;
    private final ProcessedEventStore processedEvents;
    private final ConsumerNotificationClient consumerNotifications;
    private final CertoProperties properties;
    private final Clock clock;

    public ProviderCertificateService(ProviderCertificateStore certificates, ProviderCertificateExchangeStore exchanges,
                                      CloudEventCodec codec, ProcessedEventStore processedEvents,
                                      ConsumerNotificationClient consumerNotifications,
                                      CertoProperties properties, Clock clock) {
        this.certificates = certificates;
        this.exchanges = exchanges;
        this.codec = codec;
        this.processedEvents = processedEvents;
        this.consumerNotifications = consumerNotifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange} (CX-0135 &sect;4.4.1). Each request opens a
     * new exchange (so a re-attempt after a terminal outcome is a distinct exchange, &sect;2.1.1):
     * <ul>
     *   <li>an unoffered type terminates immediately at {@code DECLINED};</li>
     *   <li>a certificate already held that covers the requested locations is fulfilled at once
     *       ({@code FULFILLED});</li>
     *   <li>otherwise the request is accepted ({@code ACKNOWLEDGED}) and fulfilled asynchronously —
     *       the certificate id/version are allocated now, the certificate is published only when the
     *       exchange reaches {@code FULFILLED} (see {@link #advance(String)}).</li>
     * </ul>
     */
    public CertificateRequestResponse requestCertificate(CertificateRequest request) {
        var exchangeId = newExchangeId();
        var counterparty = properties.consumer().bpn();
        var requestedLocations = request.locationBpns() == null ? List.<String>of() : request.locationBpns();

        if (!OFFERED_TYPES.contains(request.certificateType())) {
            // Every request opens an exchange — even a declined one — so the outcome stays correlatable.
            var certificateId = newCertificateId();
            var errors = List.of(new StatusError(
                    "Certificate type '" + request.certificateType() + "' is not offered"));
            var exchange = new ProviderCertificateExchange(
                    exchangeId, certificateId, 1, counterparty, FulfillmentStatus.DECLINED, errors);
            exchange.markConsumerInitiated();
            exchanges.save(exchange);
            LOG.info("Declined request for type {} (exchange {})", request.certificateType(), exchangeId);
            return new CertificateRequestResponse(exchangeId, certificateId, 1, FulfillmentStatus.DECLINED, errors);
        }

        var held = findHeldCertificate(request.certificateType(), requestedLocations);
        if (held.isPresent()) {
            var certificate = held.get();
            var version = certificate.latestVersion().version();
            var exchange = new ProviderCertificateExchange(
                    exchangeId, certificate.certificateId(), version, counterparty, FulfillmentStatus.FULFILLED);
            exchange.markConsumerInitiated();
            exchanges.save(exchange);
            LOG.info("Fulfilled request for type {} -> held certificate {} v{} (exchange {})",
                    request.certificateType(), certificate.certificateId(), version, exchangeId);
            return new CertificateRequestResponse(
                    exchangeId, certificate.certificateId(), version, FulfillmentStatus.FULFILLED, null);
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
     * Advances an in-progress exchange one Fulfillment step (demo trigger for the provider's
     * asynchronous fulfillment backend): {@code ACKNOWLEDGED → CERTIFICATION_REQUESTED → FULFILLED}, or
     * {@code → FAILED}. On reaching {@code FULFILLED} the allocated certificate is published.
     */
    public CertificateRequestStatus advance(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        if (!exchange.hasPendingFulfillment()) {
            return toRequestStatus(exchange); // already FULFILLED or terminal — nothing to advance
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
     * notification API (CX-0135 &sect;4.3.2) — the push counterpart of polling. Best-effort; the provider
     * SHOULD push at least the terminal outcomes (FULFILLED/DECLINED/FAILED).
     */
    private void pushFulfillmentStatus(ProviderCertificateExchange exchange) {
        if (!exchange.isConsumerInitiated()) {
            return;
        }
        var status = exchange.fulfillmentStatus();
        if (status != FulfillmentStatus.FULFILLED && status != FulfillmentStatus.FAILED) {
            return; // only push terminal-ish outcomes in this demo
        }
        consumerNotifications.notifyFulfillment(new FulfillmentStatusData(
                exchange.exchangeId(), exchange.certificateId(), status, exchange.fulfillmentErrors()));
    }

    /**
     * Provider-initiated push (CX-0135 &sect;2.1.1): opens a {@code Certificate Exchange} for a held
     * certificate (entering directly at {@code FULFILLED}) and notifies the consumer with a
     * {@code CertificateLifecycleStatus} {@code CREATED} event. Because the provider opens and stores
     * the exchange here, the consumer's later acceptance callback resolves against a known exchange.
     */
    public CertificatePublication publish(String certificateId, Integer version) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        var cv = (version == null)
                ? certificate.latestVersion()
                : certificate.version(version).orElseThrow(() ->
                        ApiException.notFound("Unknown version " + version + " for certificate " + certificateId));

        var exchangeId = newExchangeId();
        var exchange = new ProviderCertificateExchange(
                exchangeId, certificateId, cv.version(), properties.consumer().bpn(), FulfillmentStatus.FULFILLED);
        exchanges.save(exchange);

        var data = new LifecycleStatusData(
                exchangeId,
                certificateId,
                cv.version(),
                LifecycleStatus.CREATED,
                certificate.datasetId(),
                certificate.certificateType(),
                cv.validFrom(),
                cv.validUntil(),
                certificate.locationBpns().isEmpty() ? null : certificate.locationBpns());

        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Published certificate {} v{} as exchange {} (consumer notified: {})",
                certificateId, cv.version(), exchangeId, notified);
        return new CertificatePublication(exchangeId, certificateId, cv.version(), notified);
    }

    /**
     * Publishes a new version of an existing certificate (CX-0135 &sect;2.2: lifecycle CREATED → MODIFIED)
     * and notifies the consumer with a {@code MODIFIED} lifecycle event. Modification does not open an
     * exchange (&sect;2.2.4). The location set is fixed, so a modification keeps the same locations.
     */
    public CertificateLifecycleResult modify(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is withdrawn and cannot be modified");
        }
        var today = LocalDate.now(clock);
        var version = certificate.nextVersionNumber();
        var validUntil = today.plusYears(VALIDITY_YEARS);
        var pdf = renderPdf(certificateId, version, certificate.certificateType(), today, validUntil, certificate.locationBpns());
        certificate.addVersion(new CertificateVersion(version, today, validUntil, pdf));

        var data = new LifecycleStatusData(null, certificateId, version, LifecycleStatus.MODIFIED,
                certificate.datasetId(), certificate.certificateType(), today, validUntil,
                certificate.locationBpns().isEmpty() ? null : certificate.locationBpns());
        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Modified certificate {} -> version {} (consumer notified: {})", certificateId, version, notified);
        return new CertificateLifecycleResult(certificateId, version, LifecycleStatus.MODIFIED, notified);
    }

    /**
     * Withdraws a certificate (CX-0135 &sect;2.2: lifecycle → WITHDRAWN, terminal): it becomes
     * unretrievable and is excluded from queries, and the consumer is notified with a {@code WITHDRAWN}
     * lifecycle event. Withdrawal does not open an exchange (&sect;2.2.4).
     */
    public CertificateLifecycleResult withdraw(String certificateId) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.conflict("Certificate " + certificateId + " is already withdrawn");
        }
        certificate.withdraw();
        var version = certificate.latestVersion().version();

        // WITHDRAWN MAY omit the validity period (CX-0135 §4.3.1).
        var data = new LifecycleStatusData(null, certificateId, version, LifecycleStatus.WITHDRAWN,
                certificate.datasetId(), certificate.certificateType(), null, null, null);
        var notified = consumerNotifications.notifyLifecycle(data);
        LOG.info("Withdrew certificate {} (consumer notified: {})", certificateId, notified);
        return new CertificateLifecycleResult(certificateId, version, LifecycleStatus.WITHDRAWN, notified);
    }

    /** Returns the provider's full view of an exchange — both phases (demo/inspection; not in CX-0135). */
    public ExchangeView getExchangeView(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return new ExchangeView(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.version(),
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors(),
                exchange.acceptanceStatus(),
                exchange.acceptanceErrors());
    }

    /** Returns the current fulfillment status of an exchange (CX-0135 &sect;4.4.2). */
    public CertificateRequestStatus getRequestStatus(String exchangeId) {
        var exchange = exchanges.find(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Unknown exchangeId: " + exchangeId));
        return toRequestStatus(exchange);
    }

    private static CertificateRequestStatus toRequestStatus(ProviderCertificateExchange exchange) {
        return new CertificateRequestStatus(
                exchange.exchangeId(),
                exchange.certificateId(),
                exchange.version(),
                exchange.fulfillmentStatus(),
                exchange.fulfillmentErrors());
    }

    /** Retrieves certificate metadata and the PDF binary (CX-0135 &sect;4.4.3). */
    public RetrievedCertificate getCertificate(String certificateId, Integer version) {
        var certificate = certificates.find(certificateId)
                .orElseThrow(() -> ApiException.notFound("Unknown certificateId: " + certificateId));
        if (certificate.lifecycleStatus() == LifecycleStatus.WITHDRAWN) {
            throw ApiException.notFound("Certificate " + certificateId + " has been withdrawn");
        }

        var cv = (version == null)
                ? certificate.latestVersion()
                : certificate.version(version).orElseThrow(() ->
                        ApiException.notFound("Unknown version " + version + " for certificate " + certificateId));

        var metadata = new CertificateMetadata(
                certificate.certificateId(),
                cv.version(),
                certificate.certificateType(),
                cv.validFrom(),
                cv.validUntil(),
                certificate.locationBpns().isEmpty() ? null : certificate.locationBpns());
        return new RetrievedCertificate(metadata, cv.pdf());
    }

    /**
     * Records acceptance feedback delivered as one or more {@code CertificateAcceptanceStatus}
     * CloudEvents (CX-0135 &sect;4.4.4). Unknown exchanges are rejected with 404. The batch is atomic:
     * every event is validated before any is applied, so one bad event leaves the rest unapplied.
     * Duplicate events (same {@code source}+{@code id}) are ignored.
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
            // Acceptance may only be reported once the exchange is FULFILLED (CX-0135 §2.1.2 / §4.4.4).
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

    /** Applies each pending event exactly once, skipping duplicates (by {@code source}+{@code id}). */
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

    /** Answers a certificate query with one page of results and adjacent-page cursors (CX-0135 &sect;4.4.5). */
    public CertificatePage query(CertificateQuery query, String cursor) {
        var matches = certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> c.certificateType().equals(query.certificateType()))
                .filter(c -> matchesValidity(c, query.from(), query.to()))
                .sorted(Comparator.comparing(Certificate::certificateId))
                .map(this::toQueryResponse)
                .toList();

        var limit = query.limit() != null ? query.limit() : DEFAULT_QUERY_LIMIT;
        var offset = decodeCursor(cursor);
        if (offset < 0 || offset > matches.size()) {
            throw ApiException.badRequest("Invalid pagination cursor");
        }

        var end = Math.min(offset + limit, matches.size());
        var page = matches.subList(offset, end);

        var next = end < matches.size() ? encodeCursor(end) : null;
        var prev = offset > 0 ? encodeCursor(Math.max(0, offset - limit)) : null;

        // first/last are optional; include them only when the result is actually paginated.
        var paginated = next != null || prev != null;
        var first = paginated ? encodeCursor(0) : null;
        var last = paginated ? encodeCursor(((matches.size() - 1) / limit) * limit) : null;
        return new CertificatePage(page, next, prev, first, last);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Finds a held, non-withdrawn certificate of the given type whose fixed location set covers the
     * requested locations (CX-0135 &sect;2.2.1). An empty request applies to the legal entity and matches
     * a certificate with no location set.
     */
    private Optional<Certificate> findHeldCertificate(String certificateType, List<String> requestedLocations) {
        return certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> c.certificateType().equals(certificateType))
                .filter(c -> requestedLocations.isEmpty()
                        ? c.locationBpns().isEmpty()
                        : c.locationBpns().containsAll(requestedLocations))
                .findFirst();
    }

    /** Builds (but does not publish) a new single-version certificate covering the requested locations. */
    private Certificate buildCertificate(String certificateId, String certificateType, List<String> locationBpns) {
        var today = LocalDate.now(clock);
        var datasetId = "dataset-" + UUID.randomUUID();
        var certificate = new Certificate(certificateId, datasetId, certificateType, locationBpns);
        var validUntil = today.plusYears(VALIDITY_YEARS);
        var pdf = renderPdf(certificateId, 1, certificateType, today, validUntil, certificate.locationBpns());
        certificate.addVersion(new CertificateVersion(1, today, validUntil, pdf));
        return certificate;
    }

    private byte[] renderPdf(String certificateId, int version, String type,
                             LocalDate validFrom, LocalDate validUntil, List<String> locationBpns) {
        return PdfGenerator.generate("Company Certificate: " + type, List.of(
                "Certificate ID: " + certificateId,
                "Version: " + version,
                "Type: " + type,
                "Valid from: " + validFrom,
                "Valid until: " + validUntil,
                "Locations: " + (locationBpns.isEmpty() ? "legal entity" : String.join(", ", locationBpns)),
                "Issued by: " + properties.provider().bpn()));
    }

    private CertificateQueryResponse toQueryResponse(Certificate c) {
        var latest = c.latestVersion();
        return new CertificateQueryResponse(
                c.certificateId(),
                latest.version(),
                c.datasetId(),
                c.certificateType(),
                latest.validFrom(),
                latest.validUntil(),
                c.locationBpns().isEmpty() ? null : c.locationBpns());
    }

    private static boolean matchesValidity(Certificate c, LocalDate from, LocalDate to) {
        var latest = c.latestVersion();
        if (from != null && latest.validFrom().isBefore(from)) {
            return false;
        }
        return to == null || !latest.validUntil().isAfter(to);
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
