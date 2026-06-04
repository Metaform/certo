package org.metaform.certo.provider.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * JSON metadata accompanying a retrieved certificate (CX-0135 &sect;4.4.3), carried as the
 * {@code application/json} part of the {@code multipart/related} retrieval response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateMetadata(
        String certificateId,
        int version,
        String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<String> locationBpns) {
}
