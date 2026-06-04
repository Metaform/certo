package org.metaform.certo.consumer.client;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * The JSON metadata part of a retrieved certificate (CX-0135 &sect;4.4.3), as seen by the consumer.
 * A consumer-local view of the provider's metadata, decoupled from the provider's own DTOs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateMetadata(
        String certificateId,
        int version,
        String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<String> locationBpns) {
}
