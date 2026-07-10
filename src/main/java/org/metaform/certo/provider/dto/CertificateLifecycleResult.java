package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.LifecycleStatus;

/**
 * Result of a provider lifecycle action (demo): the certificate's new lifecycle state after a
 * {@code MODIFIED} (new version) or {@code WITHDRAWN}, and whether the consumer was notified.
 */
public record CertificateLifecycleResult(
        String certificateId,
        int revision,
        LifecycleStatus lifecycleStatus,
        boolean consumerNotified) {
}
