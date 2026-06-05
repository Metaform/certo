package org.metaform.certo.provider.api;

import jakarta.validation.Valid;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.provider.ProviderCertificateService;
import org.metaform.certo.provider.api.dto.CertificateLifecycleResult;
import org.metaform.certo.provider.api.dto.CertificateMetadata;
import org.metaform.certo.provider.api.dto.CertificatePage;
import org.metaform.certo.provider.api.dto.CertificatePublication;
import org.metaform.certo.provider.api.dto.CertificateQuery;
import org.metaform.certo.provider.api.dto.CertificateRequest;
import org.metaform.certo.provider.api.dto.CertificateRequestResponse;
import org.metaform.certo.provider.api.dto.CertificateRequestStatus;
import org.metaform.certo.provider.api.dto.ExchangeView;
import org.metaform.certo.provider.api.dto.RetrievedCertificate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Certificate Provider API (CX-0135 &sect;4.4). DSP control-plane concerns (catalog, contract
 * negotiation, token-refresh authorization) are out of scope.
 */
@RestController
public class ProviderCertificateController {

    private static final MediaType MULTIPART_RELATED = new MediaType("multipart", "related");
    // RFC 2387 Content-IDs identifying the two parts; `start` names the JSON part as the root.
    private static final String JSON_PART_ID = "<metadata@certo>";
    private static final String PDF_PART_ID = "<certificate@certo>";

    private final ProviderCertificateService service;
    private final ObjectMapper mapper;

    public ProviderCertificateController(ProviderCertificateService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** {@code POST /certificate-requests} — open a consumer-initiated exchange (CX-0135 &sect;4.4.1). */
    @PostMapping(path = "/certificate-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateRequestResponse> createRequest(@Valid @RequestBody CertificateRequest request) {
        var response = service.requestCertificate(request);
        return ResponseEntity.accepted().body(response);
    }

    /** {@code GET /certificate-requests/{id}} — poll fulfillment status (CX-0135 &sect;4.4.2). */
    @GetMapping(path = "/certificate-requests/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus getRequestStatus(@PathVariable("id") String exchangeId) {
        return service.getRequestStatus(exchangeId);
    }

    /**
     * {@code POST /certificate-requests/{id}/advance} — demo trigger that advances an in-progress
     * exchange one Fulfillment step (ACKNOWLEDGED → CERTIFICATION_REQUESTED → FULFILLED, or FAILED). In
     * a real deployment the provider's fulfillment backend would drive this over time; not in CX-0135.
     */
    @PostMapping(path = "/certificate-requests/{id}/advance", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateRequestStatus advanceRequest(@PathVariable("id") String exchangeId) {
        return service.advance(exchangeId);
    }

    /**
     * {@code POST /certificates/{id}/publish} — provider-initiated push (demo trigger): open an
     * exchange for a held certificate and notify the consumer with a lifecycle CREATED event. In a
     * real deployment this would be driven by the provider's own business logic, not an API call.
     */
    @PostMapping(path = "/certificates/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificatePublication> publish(@PathVariable("id") String certificateId,
                                                          @RequestParam(value = "version", required = false) Integer version) {
        return ResponseEntity.accepted().body(service.publish(certificateId, version));
    }

    /**
     * {@code POST /certificates/{id}/modify} — publish a new version of a certificate (lifecycle
     * CREATED → MODIFIED) and notify the consumer (demo trigger; CX-0135 &sect;2.2).
     */
    @PostMapping(path = "/certificates/{id}/modify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertificateLifecycleResult> modify(@PathVariable("id") String certificateId) {
        return ResponseEntity.accepted().body(service.modify(certificateId));
    }

    /**
     * {@code POST /certificates/{id}/withdraw} — withdraw a certificate (lifecycle → WITHDRAWN), making
     * it unretrievable, and notify the consumer (demo trigger; CX-0135 &sect;2.2).
     */
    @PostMapping(path = "/certificates/{id}/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    public CertificateLifecycleResult withdraw(@PathVariable("id") String certificateId) {
        return service.withdraw(certificateId);
    }

    /**
     * {@code GET /certificate-exchanges/{id}} — the provider's full view of an exchange, both phases
     * (demo/inspection; not part of CX-0135). Lets callers confirm a consumer's acceptance callback
     * was recorded.
     */
    @GetMapping(path = "/certificate-exchanges/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExchangeView getExchange(@PathVariable("id") String exchangeId) {
        return service.getExchangeView(exchangeId);
    }

    /**
     * {@code GET /certificates/{id}} — retrieve certificate metadata + PDF as {@code multipart/related}
     * (CX-0135 &sect;4.4.3).
     */
    @GetMapping("/certificates/{id}")
    public ResponseEntity<byte[]> getCertificate(@PathVariable("id") String certificateId,
                                                 @RequestParam(value = "version", required = false) Integer version,
                                                 @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        requireMultipartRelatedAcceptable(accept);

        var retrieved = service.getCertificate(certificateId, version);
        var boundary = "certo-" + Long.toHexString(System.nanoTime());
        var body = buildMultipart(retrieved.metadata(), retrieved.pdf(), boundary);

        // Set the header as a string: the parameter values (a media type, and a quoted Content-ID) are
        // not bare tokens, which MediaType's parameter validation would otherwise reject. `start` names
        // the root part by its Content-ID per RFC 2387.
        var contentType = "multipart/related; boundary=" + boundary
                + "; type=\"application/json\"; start=\"" + JSON_PART_ID + "\"";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, contentType).body(body);
    }

    /** Rejects a request whose {@code Accept} header cannot accept {@code multipart/related} (406). */
    private static void requireMultipartRelatedAcceptable(String accept) {
        if (accept == null || accept.isBlank()) {
            return; // no preference -> the endpoint's multipart/related is fine
        }
        var acceptable = MediaType.parseMediaTypes(accept).stream().anyMatch(MULTIPART_RELATED::isCompatibleWith);
        if (!acceptable) {
            throw new ApiException(HttpStatus.NOT_ACCEPTABLE,
                    "This endpoint produces multipart/related; the Accept header does not allow it");
        }
    }

    /**
     * {@code POST /certificate-acceptance-notifications} — receive acceptance status CloudEvents
     * (CX-0135 &sect;4.4.4). Accepts a single event or a batch.
     */
    @PostMapping(path = "/certificate-acceptance-notifications",
            consumes = {CcmEvents.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Void> postAcceptance(@RequestBody byte[] body) {
        service.recordAcceptance(body);
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /certificates/query} — query certificates with cursor pagination (CX-0135 &sect;4.4.5). */
    @PostMapping(path = "/certificates/query",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<?>> query(@Valid @RequestBody CertificateQuery query,
                                         @RequestParam(value = "cursor", required = false) String cursor,
                                         UriComponentsBuilder uriBuilder) {
        var page = service.query(query, cursor);

        var builder = ResponseEntity.ok();
        var link = buildLinkHeader(page, uriBuilder);
        if (link != null) {
            builder.header(HttpHeaders.LINK, link);
        }
        return builder.body(page.items());
    }

    // --- multipart assembly --------------------------------------------------------------------

    private byte[] buildMultipart(CertificateMetadata metadata, byte[] pdf, String boundary) {
        try {
            var json = mapper.writeValueAsBytes(metadata);
            var out = new ByteArrayOutputStream();
            var crlf = "\r\n";

            writeAscii(out, "--" + boundary + crlf);
            writeAscii(out, "Content-Type: " + MediaType.APPLICATION_JSON_VALUE + crlf);
            writeAscii(out, "Content-ID: " + JSON_PART_ID + crlf + crlf);
            out.write(json, 0, json.length);
            writeAscii(out, crlf);

            writeAscii(out, "--" + boundary + crlf);
            writeAscii(out, "Content-Type: " + MediaType.APPLICATION_PDF_VALUE + crlf);
            writeAscii(out, "Content-ID: " + PDF_PART_ID + crlf + crlf);
            out.write(pdf, 0, pdf.length);
            writeAscii(out, crlf);

            writeAscii(out, "--" + boundary + "--" + crlf);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to assemble multipart response");
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String buildLinkHeader(CertificatePage page, UriComponentsBuilder uriBuilder) {
        var links = new ArrayList<String>();
        if (page.nextCursor() != null) {
            links.add(link(uriBuilder, page.nextCursor(), "next"));
        }
        if (page.prevCursor() != null) {
            links.add(link(uriBuilder, page.prevCursor(), "prev"));
        }
        if (page.firstCursor() != null) {
            links.add(link(uriBuilder, page.firstCursor(), "first"));
        }
        if (page.lastCursor() != null) {
            links.add(link(uriBuilder, page.lastCursor(), "last"));
        }
        return links.isEmpty() ? null : String.join(", ", links);
    }

    private static String link(UriComponentsBuilder uriBuilder, String cursor, String rel) {
        var url = uriBuilder.cloneBuilder()
                .replacePath("/certificates/query")
                .replaceQueryParam("cursor", cursor)
                .build()
                .toUriString();
        return "<" + url + ">; rel=\"" + rel + "\"";
    }
}
