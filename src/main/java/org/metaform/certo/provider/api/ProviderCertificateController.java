package org.metaform.certo.provider.api;

import jakarta.validation.Valid;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.provider.ProviderCertificateService;
import org.metaform.certo.provider.api.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.api.dto.CertificatePage;
import org.metaform.certo.provider.api.dto.CertificatePublication;
import org.metaform.certo.provider.api.dto.CertificateQuery;
import org.metaform.certo.provider.api.dto.CertificateRequest;
import org.metaform.certo.provider.api.dto.CertificateRequestResponse;
import org.metaform.certo.provider.api.dto.CertificateRequestStatus;
import org.metaform.certo.provider.api.dto.ExchangeView;
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
 * The Certificate Provider API (CX-0135 v3, &sect;3.3). DSP control-plane concerns (catalog, contract
 * negotiation, token-refresh authorization) are out of scope.
 */
@RestController
public class ProviderCertificateController {

    private final ProviderCertificateService service;

    public ProviderCertificateController(ProviderCertificateService service) {
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
     * {@code POST /certificate-requests/{id}/advance} — demo trigger that advances an in-progress
     * exchange one Fulfillment step (not part of CX-0135; a real fulfillment backend would drive this).
     */
    @PostMapping(path = "/certificate-requests/{id}/advance", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus advanceRequest(@PathVariable("id") String exchangeId) {
        return service.advance(exchangeId);
    }

    /**
     * {@code POST /certificates/{id}/publish} — provider-initiated push (demo trigger): open an
     * exchange for a held certificate and notify the consumer with a lifecycle CREATED event.
     */
    @PostMapping(path = "/certificates/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificatePublication> publish(@PathVariable("id") String certificateId,
                                                          @RequestParam(value = "revision", required = false) Integer revision) {
        return ResponseEntity.accepted().body(service.publish(certificateId, revision));
    }

    /** {@code POST /certificates/{id}/modify} — publish a new revision and notify (demo trigger; CX-0135 &sect;2.2). */
    @PostMapping(path = "/certificates/{id}/modify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateLifecycleResult> modify(@PathVariable("id") String certificateId) {
        return ResponseEntity.accepted().body(service.modify(certificateId));
    }

    /** {@code POST /certificates/{id}/withdraw} — withdraw a certificate and notify (demo trigger; CX-0135 &sect;2.2). */
    @PostMapping(path = "/certificates/{id}/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateLifecycleResult withdraw(@PathVariable("id") String certificateId) {
        return service.withdraw(certificateId);
    }

    /**
     * {@code GET /certificate-exchanges/{id}} — the provider's full view of an exchange, both phases
     * (demo/inspection; not part of CX-0135).
     */
    @GetMapping(path = "/certificate-exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView getExchange(@PathVariable("id") String exchangeId) {
        return service.getExchangeView(exchangeId);
    }

    /**
     * {@code GET /certificates/{id}} — retrieve certificate metadata as JSON (CX-0135 &sect;3.3.2). Always
     * returns the latest revision as the full record for an active certificate, or the minimal
     * withdrawn-status body for a withdrawn one. Document binaries are not included; each is referenced in
     * {@code documents[]} and fetched via {@code GET /documents/{id}}.
     */
    @GetMapping(path = "/certificates/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getCertificate(@PathVariable("id") String certificateId) {
        return service.getCertificate(certificateId);
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
        return builder.body(page.items());
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
