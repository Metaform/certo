package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.LifecycleStatus;

/**
 * Result of a single-target lifecycle notify: what was communicated (the certificate's current lifecycle
 * state and the revision it was derived from) and whether that one consumer accepted the push. A
 * {@code false} {@code consumerNotified} is the caller's signal to retry this counterparty — delivery
 * state is not tracked here.
 */
public record LifecycleNotification(
        String certificateId,
        int revision,
        LifecycleStatus lifecycleStatus,
        boolean consumerNotified) {
}
