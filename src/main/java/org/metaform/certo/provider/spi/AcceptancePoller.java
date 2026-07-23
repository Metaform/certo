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
 * <p>Only the native v3 consumer exposes this pull endpoint; a v2.4.0 consumer has no acceptance-status GET
 * (it pushes {@code /companycertificate/status}), so polling does not apply there.
 */
public interface AcceptancePoller {

    /**
     * Returns the consumer's current acceptance for the exchange, or empty if it has not decided yet
     * ({@code 404} from the consumer). Throws {@link IOException} on a transport failure.
     */
    Optional<AcceptanceStatusData> pollAcceptance(String exchangeId, OutboundCall call) throws IOException;
}
