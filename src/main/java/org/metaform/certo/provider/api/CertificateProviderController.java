package org.metaform.certo.provider.api;

import jakarta.validation.Valid;
import org.metaform.certo.common.cloudevent.CcmEvents;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.provider.CertificateProviderService;
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
public class CertificateProviderController {

    private final CertificateProviderService service;
    private final ObjectMapper mapper;

    public CertificateProviderController(CertificateProviderService service, ObjectMapper mapper) {
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
                                                 @RequestParam(value = "version", required = false) Integer version) {
        var retrieved = service.getCertificate(certificateId, version);
        var boundary = "certo-" + Long.toHexString(System.nanoTime());
        var body = buildMultipart(retrieved.metadata(), retrieved.pdf(), boundary);

        // Set the header as a string: the `type` parameter value (application/json) is not a bare
        // token, so it must be quoted — which MediaType's parameter validation would otherwise reject.
        var contentType = "multipart/related; boundary=" + boundary + "; type=\"application/json\"";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, contentType).body(body);
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
            writeAscii(out, "Content-Type: " + MediaType.APPLICATION_JSON_VALUE + crlf + crlf);
            out.write(json, 0, json.length);
            writeAscii(out, crlf);

            writeAscii(out, "--" + boundary + crlf);
            writeAscii(out, "Content-Type: " + MediaType.APPLICATION_PDF_VALUE + crlf + crlf);
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
