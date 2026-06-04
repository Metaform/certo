package org.metaform.certo.provider;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.cloudevent.CloudEventCodec;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.pdf.PdfGenerator;
import org.metaform.certo.common.web.ApiException;
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
import org.metaform.certo.provider.model.CertificateExchange;
import org.metaform.certo.provider.model.CertificateVersion;
import org.metaform.certo.provider.store.CertificateStore;
import org.metaform.certo.provider.store.ExchangeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
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
public class CertificateProviderService {

    private static final Logger LOG = LoggerFactory.getLogger(CertificateProviderService.class);

    /** Certificate types this demo provider offers; requests for any other type are DECLINED. */
    private static final Set<String> OFFERED_TYPES = Set.of("ISO9001", "ISO14001", "IATF16949");

    private static final int DEFAULT_QUERY_LIMIT = 50;
    private static final int VALIDITY_YEARS = 3;

    private final CertificateStore certificates;
    private final ExchangeStore exchanges;
    private final CloudEventCodec codec;
    private final ConsumerNotificationClient consumerNotifications;
    private final CertoProperties properties;
    private final Clock clock;

    public CertificateProviderService(CertificateStore certificates, ExchangeStore exchanges,
                                      CloudEventCodec codec, ConsumerNotificationClient consumerNotifications,
                                      CertoProperties properties, Clock clock) {
        this.certificates = certificates;
        this.exchanges = exchanges;
        this.codec = codec;
        this.consumerNotifications = consumerNotifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens a consumer-initiated {@code Certificate Exchange} (CX-0135 &sect;4.4.1). Offered types
     * the provider already holds (or can immediately produce) are fulfilled at once; unoffered types
     * open an exchange that terminates immediately at DECLINED.
     */
    public CertificateRequestResponse requestCertificate(CertificateRequest request) {
        var exchangeId = newExchangeId();
        var counterparty = properties.consumer().bpn();

        if (!OFFERED_TYPES.contains(request.certificateType())) {
            // Every request opens an exchange — even a declined one — so the outcome stays correlatable.
            var certificateId = newCertificateId();
            var errors = List.of(new StatusError(
                    "Certificate type '" + request.certificateType() + "' is not offered for the requested location"));
            var exchange =
                    new CertificateExchange(exchangeId, certificateId, 1, counterparty, FulfillmentStatus.DECLINED);
            exchange.setFulfillment(FulfillmentStatus.DECLINED, errors);
            exchanges.save(exchange);
            LOG.info("Declined request for type {} (exchange {})", request.certificateType(), exchangeId);
            return new CertificateRequestResponse(exchangeId, certificateId, 1, FulfillmentStatus.DECLINED, errors);
        }

        var certificate = findActiveByType(request.certificateType())
                .orElseGet(() -> produce(request.certificateType(), request.locationBpns()));
        var version = certificate.latestVersion().version();

        var exchange = new CertificateExchange(
                exchangeId, certificate.certificateId(), version, counterparty, FulfillmentStatus.FULFILLED);
        exchanges.save(exchange);
        LOG.info("Fulfilled request for type {} -> certificate {} v{} (exchange {})",
                request.certificateType(), certificate.certificateId(), version, exchangeId);
        return new CertificateRequestResponse(
                exchangeId, certificate.certificateId(), version, FulfillmentStatus.FULFILLED, null);
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
        var exchange = new CertificateExchange(
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
     * CloudEvents (CX-0135 &sect;4.4.4). Unknown exchanges are rejected with 404.
     */
    public void recordAcceptance(byte[] body) {
        var nodes = codec.toEventNodes(body);
        for (var node : nodes) {
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
            exchange.setAcceptance(data.status(), data.errors());
            LOG.info("Recorded acceptance {} for exchange {}", data.status(), data.exchangeId());
        }
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
        return new CertificatePage(page, next, prev);
    }

    // --- helpers -------------------------------------------------------------------------------

    private Optional<Certificate> findActiveByType(String certificateType) {
        return certificates.all().stream()
                .filter(c -> c.lifecycleStatus() != LifecycleStatus.WITHDRAWN)
                .filter(c -> c.certificateType().equals(certificateType))
                .findFirst();
    }

    /** Produces and publishes a new certificate of the given type (used when none is already held). */
    public Certificate produce(String certificateType, List<String> locationBpns) {
        var today = LocalDate.now(clock);
        var certificateId = newCertificateId();
        var datasetId = "dataset-" + UUID.randomUUID();
        var certificate = new Certificate(certificateId, datasetId, certificateType, locationBpns);
        var validUntil = today.plusYears(VALIDITY_YEARS);
        var pdf = renderPdf(certificateId, 1, certificateType, today, validUntil, certificate.locationBpns());
        certificate.addVersion(new CertificateVersion(1, today, validUntil, pdf));
        certificates.save(certificate);
        LOG.info("Produced certificate {} of type {}", certificateId, certificateType);
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
