package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.security.outbound.OutboundCall;

import java.io.IOException;
import java.util.Optional;

/**
 * Provider-side pull of a consumer's acceptance decision — the recovery path when the consumer's pushed
 * acceptance report was lost. The provider GETs the consumer's {@code /certificate-acceptance-status/{id}}
 * on a fresh flow, so a lost best-effort push doesn't require a durable outbox.
 *
 * <p><b>This is a single-protocol capability, not a multiplexed port.</b> Unlike {@code ConsumerNotifier}
 * and {@code AcceptanceReporter} — which are dispatched by protocol (a {@code Dispatching…} facade picks
 * the adapter from the exchange's binding) — polling assumes the consumer exposes the acceptance-status GET.
 * There is a single implementation, injected directly with no dispatcher; the caller guards on
 * {@link #protocolId()} matching the exchange's binding before polling.
 */
public interface AcceptancePoller {

    /** The open protocol id this poller speaks. */
    String protocolId();

    /**
     * Returns the consumer's current acceptance for the exchange, or empty if it has not decided yet
     * ({@code 404} from the consumer). Throws {@link IOException} on a transport failure.
     */
    Optional<AcceptanceStatusData> pollAcceptance(String exchangeId, OutboundCall call) throws IOException;
}
