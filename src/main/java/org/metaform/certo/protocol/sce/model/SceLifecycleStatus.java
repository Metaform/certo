package org.metaform.certo.protocol.sce.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.LifecycleStatus;

/**
 * The the SCE specification <b>v3</b> wire payload of a {@code CertificateLifecycleStatus} CloudEvent — the
 * {@code data} member. It carries the certificate as the v3 wire {@link SceCertificate}; the core works
 * with the neutral {@code LifecycleStatusData} (domain), and the v3 notify/receive adapters map between
 * them via {@link org.metaform.certo.protocol.sce.SceCertificateCodec}. This keeps the version-specific
 * certificate shape out of the domain event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceLifecycleStatus(
        LifecycleStatus status,
        SceCertificate certificate) {
}
