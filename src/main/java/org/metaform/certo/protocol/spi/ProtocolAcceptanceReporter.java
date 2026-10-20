package org.metaform.certo.protocol.spi;

import org.metaform.certo.protocol.domain.ExchangeBinding;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;

import java.util.List;

/**
 * A protocol-specific renderer/sender for consumer &rarr; provider acceptance reporting. One implementation
 * per protocol registers itself under {@link #protocolId()}; {@link DispatchingAcceptanceReporter}
 * selects the right one from the exchange's {@link ExchangeBinding} and delegates. Each adapter renders its
 * own wire format; the token and provider endpoint are resolved from the siglet cache via the
 * {@link OutboundCall}'s flow. The {@code binding} carries per-exchange detail for adapters that need it.
 */
public interface ProtocolAcceptanceReporter {

    /** The open protocol id this adapter speaks (e.g. {@code "sce-1.0"}). */
    String protocolId();

    /**
     * Reports the acceptance outcome to the provider. {@code binding} is null for the native protocol.
     * {@code call} carries the sender participant context, the counterparty identity, and the live
     * outbound flow the secured adapter resolves its token + endpoint from. Returns {@code true} on successful
     * delivery (a {@code 2xx}); {@code false} on any delivery failure (never throws).
     */
    boolean report(ExchangeBinding binding,
                   String exchangeId,
                   AcceptanceStatus status,
                   List<StatusError> errors,
                   OutboundCall call);
}
