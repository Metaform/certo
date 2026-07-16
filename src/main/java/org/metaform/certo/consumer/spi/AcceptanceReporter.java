package org.metaform.certo.consumer.spi;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.OutboundCall;

import java.util.List;

/**
 * Outbound port for consumer &rarr; provider acceptance reporting. The {@code @Primary} implementation is
 * {@code org.metaform.certo.protocol.DispatchingAcceptanceReporter}, which routes by protocol version to a
 * per-version {@code ProtocolAcceptanceReporter}: v3 ({@code org.metaform.certo.protocol.ccm300.consumer.Ccm300Reporter})
 * sends a v3 CloudEvent, v2.4.0 ({@code org.metaform.certo.protocol.ccm240.consumer.Ccm240Reporter})
 * posts to the v2.4.0 {@code /companycertificate/status}.
 */
public interface AcceptanceReporter {

    /**
     * Reports the consumer's acceptance outcome for an exchange to the provider (best-effort). {@code call}
     * carries the sender participant context, the provider (counterparty) BPN, and the live outbound flow the
     * secured adapter resolves its token + endpoint from.
     */
    void report(String exchangeId, String certificateId, AcceptanceStatus status, OutboundCall call, List<StatusError> errors);
}
