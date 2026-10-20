package org.metaform.certo.protocol;

import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolRetriever;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing certificate retriever: selects the counterparty's protocol and delegates to that
 * protocol's {@link ProtocolRetriever}. The retrieval analogue of {@link DispatchingConsumerNotifier} /
 * {@link DispatchingAcceptanceReporter}.
 *
 * <p>Two entry points, because the protocol is known two ways:
 * <ul>
 *   <li>{@link #fetchForExchange(String, String, OutboundCall)} — an exchange-scoped {@code retrieve}: the
 *       protocol is read from the exchange's {@link ExchangeBinding} (role
 *       {@link CounterpartyRole#PROVIDER}) — every exchange has one, recorded when it was opened.</li>
 *   <li>{@link #fetch(String, String, OutboundCall)} — a proactive discovery pull, where no exchange
 *       exists and the caller states the partner's protocol id explicitly (<b>required</b>).</li>
 * </ul>
 */
@Component
public class DispatchingCertificateRetriever {

    private final Map<String, ProtocolRetriever> byId;
    private final ExchangeBindingStore bindings;

    public DispatchingCertificateRetriever(List<ProtocolRetriever> retrievers, ExchangeBindingStore bindings) {
        this.byId = retrievers.stream().collect(Collectors.toMap(ProtocolRetriever::protocolId, Function.identity()));
        this.bindings = bindings;
    }

    /**
     * Pulls an exchange's certificate in the protocol recorded on the exchange's binding, pinned to the
     * revision the exchange concerns (the certificate may have been modified since fulfillment).
     */
    public RetrievedCertificate fetchForExchange(String exchangeId, String certificateId, Integer revision,
                                                 OutboundCall call) throws IOException {
        var protocolId = bindings.resolve(exchangeId, CounterpartyRole.PROVIDER)
                .map(ExchangeBinding::protocolId)
                .orElseThrow(() -> new IllegalStateException(
                        "No protocol binding recorded for exchange " + exchangeId));
        return retriever(protocolId).fetch(certificateId, revision, call);
    }

    /** Pulls in an explicitly-named protocol — a proactive discovery pull with no bound exchange (latest revision). */
    public RetrievedCertificate fetch(String protocolId, String certificateId, OutboundCall call) throws IOException {
        return retriever(protocolId).fetch(certificateId, null, call);
    }

    private ProtocolRetriever retriever(String protocolId) {
        var retriever = byId.get(protocolId);
        if (retriever == null) {
            throw ApiException.badRequest("Unsupported protocol: " + protocolId);
        }
        return retriever;
    }
}
