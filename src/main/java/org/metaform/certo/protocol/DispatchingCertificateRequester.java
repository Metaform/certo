package org.metaform.certo.protocol;

import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolRequester;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing certificate requester: selects the provider's protocol and delegates to that protocol's
 * {@link ProtocolRequester}. The request analogue of {@link DispatchingConsumerNotifier} /
 * {@link DispatchingAcceptanceReporter} / {@link DispatchingCertificateRetriever}.
 *
 * <p>The protocol is known two ways: opening a request, before any exchange exists, the caller states the
 * provider's protocol id explicitly (<b>required</b> on the initiate request); polling, the exchange
 * exists, so the protocol is read from its {@link ExchangeBinding} (role
 * {@link CounterpartyRole#PROVIDER}) — every exchange has one, recorded when it was opened.
 */
@Component
public class DispatchingCertificateRequester {

    private final Map<String, ProtocolRequester> byId;
    private final ExchangeBindingStore bindings;

    public DispatchingCertificateRequester(List<ProtocolRequester> requesters, ExchangeBindingStore bindings) {
        this.byId = requesters.stream().collect(Collectors.toMap(ProtocolRequester::protocolId, Function.identity()));
        this.bindings = bindings;
    }

    /** Opens a request against an explicitly named provider protocol. */
    public ProviderRequestResult request(String protocolId, String messageId, String certificateType,
                                         List<LocationFilter> certifiedLocations, OutboundCall call) throws IOException {
        return requester(protocolId).request(messageId, certificateType, certifiedLocations, call);
    }

    public ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException {
        var protocolId = bindings.resolve(exchangeId, CounterpartyRole.PROVIDER)
                .map(ExchangeBinding::protocolId)
                .orElseThrow(() -> new IllegalStateException(
                        "No protocol binding recorded for exchange " + exchangeId));
        return requester(protocolId).pollStatus(exchangeId, call);
    }

    private ProtocolRequester requester(String protocolId) {
        var requester = byId.get(protocolId);
        if (requester == null) {
            throw ApiException.badRequest("Unsupported protocol: " + protocolId);
        }
        return requester;
    }
}
