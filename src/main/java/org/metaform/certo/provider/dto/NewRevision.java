package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.List;

/**
 * The body of the management "add revision" call — a new version of an existing certificate the backend has
 * issued. A revision varies only the validity window and the documents (uploaded first via
 * {@code POST /management/v1/documents}); the certificate's other metadata (type, locations, issuer, …) is
 * carried over. {@code validFrom}, {@code validUntil}, and a non-empty {@code documentIds} are required.
 */
public record NewRevision(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<String> documentIds) {
}
