package org.metaform.certo.protocol;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * A version-specific renderer/sender for consumer &rarr; provider acceptance reporting. One implementation
 * per protocol version registers itself under {@link #version()}; {@link DispatchingAcceptanceReporter}
 * selects the right one from the exchange's {@link ExchangeBinding}. The v3 implementation ignores the
 * binding and sends a CloudEvent to the configured provider; other versions render their own message to
 * {@code binding.callbackUrl()}.
 */
public interface ProtocolAcceptanceReporter {

    /** The protocol version this adapter speaks (see {@link ProtocolVersions}). */
    String version();

    /** Reports the acceptance outcome to the provider. {@code binding} is null for the native version. */
    void report(ExchangeBinding binding, String exchangeId, String certificateId,
                AcceptanceStatus status, List<StatusError> errors);
}
