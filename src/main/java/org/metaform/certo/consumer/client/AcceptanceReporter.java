package org.metaform.certo.consumer.client;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Outbound port for consumer &rarr; provider acceptance reporting. The default implementation
 * ({@link ProviderAcceptanceClient}) sends a v3 CloudEvent; the backward-compat adapter provides a
 * routing implementation that redirects to a legacy v2.4.0 {@code /companycertificate/status} when the
 * exchange belongs to a legacy provider.
 */
public interface AcceptanceReporter {

    /** Reports the consumer's acceptance outcome for an exchange to the provider (best-effort). */
    void report(String exchangeId, String certificateId, AcceptanceStatus status, List<StatusError> errors);
}
