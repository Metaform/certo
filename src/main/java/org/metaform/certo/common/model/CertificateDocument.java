package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * A reference to a certificate document (CX-0135 &sect;4). The binary is retrieved separately via
 * {@code GET /documents/{id}}; this carries only the reference. A {@code documentId} is opaque and
 * revision-independent — the same document may be referenced by multiple certificate revisions.
 *
 * <p>The optional {@code contentBase64} carries the document binary inline. It is populated only in an
 * <em>embedded-document</em> push (the certificate is delivered whole in the notification, so the
 * consumer need not pull); on metadata retrieval ({@code GET /certificates/{id}}) it MUST be absent
 * (CX-0135 &sect;3.3.2), which {@code @JsonInclude(NON_NULL)} ensures.
 *
 * @param documentId    opaque, revision-independent identifier, used with {@code GET /documents/{id}}
 * @param createdDate   the date the document was created
 * @param language      ISO 639-1 two-letter language code, if the document is language-specific
 * @param mediaType     IANA media type of the document binary (e.g. {@code application/pdf})
 * @param contentBase64 the Base64-encoded document binary, inline; present only in an embedded push
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateDocument(
        String documentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate createdDate,
        String language,
        String mediaType,
        String contentBase64) {

    /** A document reference without inline content (the metadata / push-pull form). */
    public CertificateDocument(String documentId, LocalDate createdDate, String language, String mediaType) {
        this(documentId, createdDate, language, mediaType, null);
    }
}
