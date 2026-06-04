package org.metaform.certo.provider.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * A single match entry in a certificate query response (CX-0135 &sect;4.4.5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateQueryResponse(
        String certificateId,
        int version,
        String datasetId,
        String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<String> locationBpns) {
}
