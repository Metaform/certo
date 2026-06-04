package org.metaform.certo.provider.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * Request body for {@code POST /certificates/query} (CX-0135 &sect;4.4.5).
 *
 * @param certificateType opaque certificate type to match (mandatory)
 * @param from            inclusive lower bound on {@code validFrom} (optional)
 * @param to              inclusive upper bound on {@code validUntil} (optional)
 * @param limit           maximum results per page (optional)
 */
public record CertificateQuery(
        @NotBlank String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate from,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate to,
        @Min(1) Integer limit) {
}
