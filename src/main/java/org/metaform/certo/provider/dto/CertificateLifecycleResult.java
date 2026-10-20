package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.LifecycleStatus;

/**
 * Result of a provider lifecycle <b>state</b> change (revise → a new revision, still {@code AVAILABLE}, or
 * {@code WITHDRAWN}): the certificate's new lifecycle state. This is a state change only — notifying
 * consumers is a separate, explicitly-targeted {@code notify}.
 */
public record CertificateLifecycleResult(
        String certificateId,
        int revision,
        LifecycleStatus lifecycleStatus) {
}
