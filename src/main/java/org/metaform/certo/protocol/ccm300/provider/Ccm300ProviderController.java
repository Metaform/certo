package org.metaform.certo.protocol.ccm300.provider;

import jakarta.validation.Valid;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec;
import org.metaform.certo.provider.ProviderCertificateService;
import org.metaform.certo.provider.dto.CertificatePage;
import org.metaform.certo.provider.dto.CertificateQuery;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.dto.CertificateRequestResponse;
import org.metaform.certo.provider.dto.CertificateRequestStatus;
import org.metaform.certo.provider.model.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;

/**
 * The Certificate Provider API (CX-0135 v3, &sect;3.3).
 */
@RestController
public class Ccm300ProviderController {

    private final ProviderCertificateService service;

    public Ccm300ProviderController(ProviderCertificateService service) {
        this.service = service;
    }

    /** {@code POST /certificate-requests} — open a consumer-initiated exchange (CX-0135 &sect;3.3.1). */
    @PostMapping(path = "/certificate-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateRequestResponse> createRequest(@Valid @RequestBody CertificateRequest request) {
        return ResponseEntity.accepted().body(service.requestCertificate(request));
    }

    /** {@code GET /certificate-requests/{id}} — poll fulfillment status (CX-0135 &sect;3.3.1.1). */
    @GetMapping(path = "/certificate-requests/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus getRequestStatus(@PathVariable("id") String exchangeId) {
        return service.getRequestStatus(exchangeId);
    }

    /**
     * {@code GET /certificates/{id}} — retrieve certificate metadata as JSON (CX-0135 &sect;3.3.2). Always
     * returns the latest revision as the full record for an active certificate, or the minimal
     * withdrawn-status body for a withdrawn one. Document binaries are not included; each is referenced in
     * {@code documents[]} and fetched via {@code GET /documents/{id}}.
     */
    @GetMapping(path = "/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getCertificate(@PathVariable("id") String certificateId) {
        var result = service.getCertificate(certificateId);
        // Serialize the neutral domain record through the v3 wire codec; leave the withdrawn body as-is.
        return result instanceof CertificateRecord record ? Ccm300CertificateCodec.toWire(record) : result;
    }

    /**
     * {@code GET /documents/{id}} — retrieve a certificate document binary (CX-0135 &sect;3.3.3), served
     * with {@code Content-Type} set to the document's {@code mediaType}.
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> getDocument(@PathVariable("id") String documentId) {
        Document document = service.getDocument(documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, document.mediaType())
                .body(document.content());
    }

    /**
     * {@code POST /certificate-acceptance-notifications} — receive acceptance status CloudEvents
     * (CX-0135 &sect;3.3.5). Accepts a single event or a batch.
     */
    @PostMapping(path = "/certificate-acceptance-notifications",
            consumes = {CcmEvents.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> postAcceptance(@RequestBody byte[] body) {
        service.recordAcceptance(body);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /certificates/search} — search certificates with the CX-0135 &sect;3.3.4 query grammar.
     * Returns full records (no document binaries); pagination is carried in the RFC 8288 {@code Link}
     * header ({@code next}/{@code prev}). An unsupported field yields 501.
     */
    @PostMapping(path = "/certificates/search",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@Valid @RequestBody CertificateQuery query,
                                    @RequestParam(value = "limit", required = false) Integer limit,
                                    @RequestParam(value = "cursor", required = false) String cursor,
                                    UriComponentsBuilder uriBuilder) {
        var page = service.search(query, limit, cursor);
        var builder = ResponseEntity.ok();
        var link = buildLinkHeader(page, limit, uriBuilder);
        if (link != null) {
            builder.header(HttpHeaders.LINK, link);
        }
        return builder.body(page.items().stream().map(Ccm300CertificateCodec::toWire).toList());
    }

    private static String buildLinkHeader(CertificatePage page, Integer limit, UriComponentsBuilder uriBuilder) {
        var links = new ArrayList<String>();
        if (page.nextCursor() != null) {
            links.add(link(uriBuilder, page.nextCursor(), limit, "next"));
        }
        if (page.prevCursor() != null) {
            links.add(link(uriBuilder, page.prevCursor(), limit, "prev"));
        }
        return links.isEmpty() ? null : String.join(", ", links);
    }

    private static String link(UriComponentsBuilder uriBuilder, String cursor, Integer limit, String rel) {
        var b = uriBuilder.cloneBuilder().replacePath("/certificates/search").replaceQueryParam("cursor", cursor);
        if (limit != null) {
            b.replaceQueryParam("limit", limit);
        }
        return "<" + b.build().toUriString() + ">; rel=\"" + rel + "\"";
    }
}
