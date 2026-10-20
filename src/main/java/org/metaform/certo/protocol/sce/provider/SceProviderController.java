package org.metaform.certo.protocol.sce.provider;

import jakarta.validation.Valid;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.metaform.certo.common.security.inbound.SecurityTokenInterceptor;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.protocol.sce.SceCertificateCodec;
import org.metaform.certo.protocol.sce.Sce;
import org.metaform.certo.protocol.sce.model.SceExchange;
import org.metaform.certo.provider.ProviderCatalogService;
import org.metaform.certo.provider.ProviderExchangeService;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.dto.CertificateSearchFilter;
import org.metaform.certo.provider.domain.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The SCE Certificate Provider API: exchange creation and retrieval, certificate and document retrieval,
 * and search. Acceptance events arrive on the shared {@code POST /notifications} endpoint
 * ({@link org.metaform.certo.protocol.sce.SceNotificationsController}).
 */
@RestController
public class SceProviderController {

    private static final Set<String> SEARCH_FILTER_PROPERTIES = Set.of("certificateId", "certificateType", "certifiedLocations");
    private static final Set<String> LOCATION_FILTER_PROPERTIES = Set.of("legalEntityId", "siteId", "addressId");

    private final ProviderCatalogService catalogService;
    private final ProviderExchangeService exchangeService;
    private final ObjectMapper mapper;

    public SceProviderController(ProviderCatalogService catalogService, ProviderExchangeService exchangeService,
                                 ObjectMapper mapper) {
        this.catalogService = catalogService;
        this.exchangeService = exchangeService;
        this.mapper = mapper;
    }

    /**
     * {@code POST /exchanges} — create a Certificate Exchange from a certificate request. Always
     * {@code 201 Created} with the {@code Location} of the exchange and the Exchange object — including a
     * declined exchange, whose {@code status} is {@code DECLINED}. Creation is idempotent on
     * (counterparty, {@code messageId}): a retry returns the already-created exchange.
     */
    @PostMapping(path = "/exchanges",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SceExchange> createExchange(
            @Valid @RequestBody CertificateRequest request,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        // The returned view covers both phases: an idempotent replay may resolve an exchange that has
        // already progressed into the Acceptance phase, and the Exchange object reports the current state.
        var view = exchangeService.requestCertificate(request, requestContext, Sce.PROTOCOL_ID);
        return ResponseEntity.created(URI.create("/exchanges/" + view.exchangeId()))
                .body(SceExchange.of(view));
    }

    /** {@code GET /exchanges/{id}} — the exchange's current state, covering both phases; 404 when unknown. */
    @GetMapping(path = "/exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SceExchange getExchange(@PathVariable("id") String exchangeId,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        return SceExchange.of(exchangeService.getExchangeView(requestContext.participantContextId(), exchangeId));
    }

    /**
     * {@code GET /certificates/{id}?revision=} — retrieve certificate metadata as JSON: the requested (or
     * latest) revision as the full record for an active certificate, or the minimal withdrawn-status body
     * for a withdrawn one. Document binaries are not included; each is referenced in {@code documents[]}
     * and fetched via {@code GET /documents/{id}}.
     */
    @GetMapping(path = "/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getCertificate(@PathVariable("id") String certificateId,
            @RequestParam(value = "revision", required = false) Integer revision,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        var result = catalogService.getCertificate(requestContext.participantContextId(), certificateId, revision);
        // Serialize the neutral domain record through the wire codec; leave the withdrawn body as-is.
        return result instanceof CertificateRecord record ? SceCertificateCodec.toWire(record) : result;
    }

    /**
     * {@code GET /documents/{id}} — retrieve a certificate document binary, served with
     * {@code Content-Type} set to the document's {@code mediaType}.
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> getDocument(@PathVariable("id") String documentId,
            @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
            VerifiedRequestContext requestContext) {
        Document document = catalogService.getDocument(documentId, requestContext.participantContextId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, document.mediaType())
                .body(document.content());
    }

    /**
     * {@code POST /search} — search certificates with the SCE filter object (every supplied property is an
     * AND filter; an empty object matches all visible certificates). Returns full records (no document
     * binaries) as {@code { "result": [...], "cursor": "..." }}, with {@code cursor} present only when the
     * page is not the last. An unsupported filter property is rejected with 400.
     */
    @PostMapping(path = "/search",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody JsonNode body,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      @RequestParam(value = "cursor", required = false) String cursor,
                                      @RequestAttribute(name = SecurityTokenInterceptor.VERIFIED_ATTRIBUTE, required = true)
                                      VerifiedRequestContext requestContext) {
        var filter = decodeSearchFilter(body);
        var page = catalogService.search(requestContext.participantContextId(), filter, limit, cursor);
        var result = new LinkedHashMap<String, Object>();
        result.put("result", page.items().stream().map(SceCertificateCodec::toWire).toList());
        if (page.nextCursor() != null) {
            result.put("cursor", page.nextCursor());
        }
        return result;
    }

    /**
     * Binds the filter object strictly: a filter property this provider does not support MUST be rejected
     * with 400 (SCE), so unknown keys cannot be silently ignored by lenient JSON binding.
     */
    private CertificateSearchFilter decodeSearchFilter(JsonNode body) {
        if (body == null || body.isNull()) {
            return new CertificateSearchFilter(null, null, null);
        }
        if (!body.isObject()) {
            throw ApiException.badRequest("The search filter must be a JSON object");
        }
        for (var name : body.propertyNames()) {
            if (!SEARCH_FILTER_PROPERTIES.contains(name)) {
                throw ApiException.badRequest("Unsupported search filter property: " + name);
            }
        }
        var locations = body.get("certifiedLocations");
        if (locations != null && !locations.isNull()) {
            if (!locations.isArray()) {
                throw ApiException.badRequest("certifiedLocations must be an array of location filter objects");
            }
            for (var location : locations) {
                if (!location.isObject()) {
                    throw ApiException.badRequest("certifiedLocations entries must be objects");
                }
                for (var name : location.propertyNames()) {
                    if (!LOCATION_FILTER_PROPERTIES.contains(name)) {
                        throw ApiException.badRequest("Unsupported location filter property: " + name);
                    }
                }
            }
        }
        try {
            return mapper.convertValue(body, CertificateSearchFilter.class);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Malformed search filter: " + e.getMessage());
        }
    }
}
