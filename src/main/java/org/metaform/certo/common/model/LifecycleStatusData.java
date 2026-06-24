package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code data} payload of a {@code CertificateLifecycleStatus} CloudEvent (CX-0135 &sect;3.2.1),
 * sent by a provider to notify a consumer of a change to a certificate over its lifecycle.
 *
 * <p>v3 nests the certificate record under {@code certificate}. {@code CREATED} opens a
 * provider-initiated exchange and carries {@code exchangeId}; {@code MODIFIED}/{@code WITHDRAWN} do not.
 * Under the baseline consumer subject the embedded {@code certificate} carries only the light-triage
 * subset (the consumer pulls the rest) — Certo never embeds document content (push-pull).
 *
 * @param status      the lifecycle status: CREATED, MODIFIED or WITHDRAWN (mandatory)
 * @param exchangeId  the exchange opened by this event; present only for CREATED
 * @param certificate the certificate record (mandatory); subset depends on status/subject
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleStatusData(
        LifecycleStatus status,
        String exchangeId,
        CertificateRecord certificate) {
}
