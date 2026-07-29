package org.metaform.certo.protocol.spi;

import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.domain.ExchangeBinding;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;

import java.util.List;

/**
 * A version-specific renderer/sender for consumer &rarr; provider acceptance reporting. One implementation
 * per protocol version registers itself under {@link #version()}; {@link DispatchingAcceptanceReporter}
 * selects the right one from the exchange's {@link ExchangeBinding} and delegates. Each adapter renders its
 * own wire format; the token and provider endpoint are resolved from the siglet cache via the
 * {@link OutboundCall}'s flow. The v2.4.0 adapter uses the binding's per-exchange detail; the v3 adapter does not.
 */
public interface ProtocolAcceptanceReporter {

    /** The protocol version this adapter speaks (see {@link ProtocolVersion}). */
    ProtocolVersion version();

    /**
     * Reports the acceptance outcome to the provider. {@code binding} is null for the native version.
     * {@code call} carries the sender participant context, the provider (counterparty) BPN, and the live
     * outbound flow the secured adapter resolves its token + endpoint from. Returns {@code true} on successful
     * delivery (a {@code 2xx}); {@code false} on any delivery failure (never throws).
     */
    boolean report(ExchangeBinding binding,
                   String exchangeId,
                   String certificateId,
                   AcceptanceStatus status,
                   List<StatusError> errors,
                   OutboundCall call);
}
