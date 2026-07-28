package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.security.OutboundCall;

import java.io.IOException;
import java.util.Optional;

/**
 * Provider-side pull of a consumer's acceptance decision — the recovery path when the consumer's pushed
 * acceptance report was lost. The provider GETs the consumer's {@code /certificate-acceptance-status/{id}}
 * (CX-0135 &sect;4.3.3) on a fresh flow, so a lost best-effort push doesn't require a durable outbox.
 *
 * <p><b>This is a v3-only capability, not a multiplexed port.</b> Unlike {@code ConsumerNotifier} and
 * {@code AcceptanceReporter} — which are dispatched by protocol version (a {@code Dispatching…} facade picks
 * the {@code Ccm300}/{@code Ccm240} adapter from the exchange's binding) because both versions can receive
 * those messages — polling has no v2.4.0 counterpart: the v2.4.0 consumer exposes no acceptance-status GET (it
 * <em>pushes</em> {@code /companycertificate/status}). So there is a single (v3) implementation, injected
 * directly with no dispatcher, and the caller polls only a native/v3 consumer.
 */
public interface AcceptancePoller {

    /**
     * Returns the consumer's current acceptance for the exchange, or empty if it has not decided yet
     * ({@code 404} from the consumer). Throws {@link IOException} on a transport failure.
     */
    Optional<AcceptanceStatusData> pollAcceptance(String exchangeId, OutboundCall call) throws IOException;
}
