package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The {@code data} payload of a {@code CertificateLifecycleStatus} CloudEvent (CX-0135 &sect;4.3.1),
 * sent by a provider to notify a consumer of a change to a certificate over its lifecycle.
 *
 * @param exchangeId      the exchange opened by this event; present when status is CREATED, absent otherwise
 * @param certificateId   the certificate identifier (mandatory)
 * @param version         the certificate version (mandatory)
 * @param status          the lifecycle status: CREATED, MODIFIED or WITHDRAWN (mandatory)
 * @param datasetId       the DSP dataset identifier under which the certificate is exposed (mandatory)
 * @param certificateType opaque certificate type, e.g. {@code ISO9001} (mandatory)
 * @param validFrom       inclusive validity start; required for CREATED/MODIFIED, optional for WITHDRAWN
 * @param validUntil      inclusive validity end; same presence rules as validFrom
 * @param locationBpns    BPNs the certificate applies to; if omitted, applies to the legal entity
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LifecycleStatusData(
        String exchangeId,
        String certificateId,
        Integer version,
        LifecycleStatus status,
        String datasetId,
        String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<String> locationBpns) {
}
