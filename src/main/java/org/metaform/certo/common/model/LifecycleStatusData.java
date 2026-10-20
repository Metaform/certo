package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code data} payload of a {@code CertificateLifecycleStatus} event, sent by a provider to notify a
 * consumer of a change to a certificate over its lifecycle. Purely informational: it never opens, alters,
 * or closes a Certificate Exchange — a consumer that wants the certificate opens an exchange by submitting
 * a certificate request.
 *
 * <p>The carried {@code certificate} is only a subset: the light-triage fields for
 * {@code CREATED}/{@code MODIFIED} (the consumer pulls the rest), the id alone for {@code WITHDRAWN}.
 * Document content is never carried.
 *
 * @param status      the lifecycle status: CREATED, MODIFIED or WITHDRAWN (mandatory)
 * @param certificate the certificate record (mandatory); subset depends on status
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleStatusData(
        LifecycleStatus status,
        CertificateRecord certificate) {
}
