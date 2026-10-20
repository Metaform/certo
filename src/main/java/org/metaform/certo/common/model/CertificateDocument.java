package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * A reference to a certificate document. The binary is retrieved separately via
 * {@code GET /documents/{id}}; this carries only the reference — a certificate record never embeds
 * document content. A {@code documentId} is opaque and revision-independent — the same document may be
 * referenced by multiple certificate revisions.
 *
 * @param documentId  opaque, revision-independent identifier, used with {@code GET /documents/{id}}
 * @param createdDate the date the document was created
 * @param language    ISO 639-1 two-letter language code, if the document is language-specific
 * @param mediaType   IANA media type of the document binary (e.g. {@code application/pdf})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateDocument(
        String documentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate createdDate,
        String language,
        String mediaType) {
}
