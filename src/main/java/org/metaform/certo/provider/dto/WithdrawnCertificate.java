package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.LifecycleStatus;

/**
 * Minimal status body returned by {@code GET /certificates/{id}} for a withdrawn certificate
 *. A withdrawn certificate need not remain retrievable; this body lets a consumer
 * holding the {@code certificateId} still observe the withdrawal. It carries no metadata or documents.
 */
public record WithdrawnCertificate(String certificateId, LifecycleStatus status) {

    public static WithdrawnCertificate of(String certificateId) {
        return new WithdrawnCertificate(certificateId, LifecycleStatus.WITHDRAWN);
    }
}
