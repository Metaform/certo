package org.metaform.certo.protocol;

import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.consumer.spi.CertificateRetriever;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.protocol.spi.ProtocolRetriever;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing certificate retriever: selects the counterparty's protocol version and delegates to that
 * version's {@link ProtocolRetriever}. The retrieval analogue of {@link DispatchingConsumerNotifier} /
 * {@link DispatchingAcceptanceReporter}.
 *
 * <p>Two entry points, because the version is known two ways:
 * <ul>
 *   <li>{@link #fetch(String, OutboundCall)} — the {@link CertificateRetriever} port used by an
 *       exchange-scoped {@code retrieve}. The version is looked up from the exchange's
 *       {@link org.metaform.certo.protocol.domain.ExchangeBinding} via the same
 *       {@code (certificateId, peerDid)} correlation inbound status uses; no binding ⇒
 *       {@link ProtocolVersion#NATIVE}.</li>
 *   <li>{@link #fetch(ProtocolVersion, String, OutboundCall)} — a proactive discovery pull, where no
 *       exchange exists yet and the caller states the partner's protocol version explicitly.</li>
 * </ul>
 */
@Component
@Primary
public class DispatchingCertificateRetriever implements CertificateRetriever {

    private final Map<ProtocolVersion, ProtocolRetriever> byVersion;
    private final ExchangeBindingStore bindings;

    public DispatchingCertificateRetriever(List<ProtocolRetriever> retrievers, ExchangeBindingStore bindings) {
        this.byVersion = retrievers.stream().collect(Collectors.toMap(ProtocolRetriever::version, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public RetrievedCertificate fetch(String certificateId, OutboundCall call) throws IOException {
        // Correlate the exchange's binding on the verified peer DID (the provider), exactly as inbound v2.4.0
        // status does; a v2.4.0 by-reference exchange resolves to the 2.4.0 retriever, everything else to native.
        var version = bindings.findByCertificateIdAndPeerDid(certificateId, call.counterpartyDid())
                .map(binding -> binding.version())
                .orElse(ProtocolVersion.NATIVE);
        return retriever(version).fetch(certificateId, call);
    }

    /** Pulls in an explicitly-named protocol version (a proactive discovery pull with no bound exchange). */
    public RetrievedCertificate fetch(ProtocolVersion version, String certificateId, OutboundCall call) throws IOException {
        return retriever(version).fetch(certificateId, call);
    }

    private ProtocolRetriever retriever(ProtocolVersion version) {
        var retriever = byVersion.getOrDefault(version, byVersion.get(ProtocolVersion.NATIVE));
        if (retriever == null) {
            throw new IllegalStateException("No certificate retriever registered for protocol version " + version);
        }
        return retriever;
    }
}
