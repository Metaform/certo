package org.metaform.certo.protocol;

import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.consumer.spi.CertificateRequester;
import org.metaform.certo.consumer.spi.ProviderRequestResult;
import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolRequester;
import org.metaform.certo.protocol.store.ExchangeBindingStore;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing certificate requester: selects the provider's protocol version and delegates to that
 * version's {@link ProtocolRequester}. The request analogue of {@link DispatchingConsumerNotifier} /
 * {@link DispatchingAcceptanceReporter} / {@link DispatchingCertificateRetriever}.
 *
 * <p>Version is known two ways:
 * <ul>
 *   <li>{@link #request(ProtocolVersion, String, List, OutboundCall)} — opening a request, before any
 *       exchange exists, so the caller states the provider's version explicitly (from the initiate request).
 *       The port's {@link #request(String, List, OutboundCall)} defaults to {@link ProtocolVersion#NATIVE}.</li>
 *   <li>{@link #pollStatus(String, OutboundCall)} — the exchange exists, so the version is read from its
 *       {@link ExchangeBinding} (role {@link CounterpartyRole#PROVIDER}); no binding ⇒ native v3.</li>
 * </ul>
 */
@Component
@Primary
public class DispatchingCertificateRequester implements CertificateRequester {

    private final Map<ProtocolVersion, ProtocolRequester> byVersion;
    private final ExchangeBindingStore bindings;

    public DispatchingCertificateRequester(List<ProtocolRequester> requesters, ExchangeBindingStore bindings) {
        this.byVersion = requesters.stream().collect(Collectors.toMap(ProtocolRequester::version, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public ProviderRequestResult request(String certificateType, List<String> certifiedLocations, OutboundCall call)
            throws IOException {
        return requester(ProtocolVersion.NATIVE).request(certificateType, certifiedLocations, call);
    }

    /** Opens a request against an explicitly-named provider protocol version. */
    public ProviderRequestResult request(ProtocolVersion version, String certificateType,
                                         List<String> certifiedLocations, OutboundCall call) throws IOException {
        return requester(version).request(certificateType, certifiedLocations, call);
    }

    @Override
    public ProviderRequestResult pollStatus(String exchangeId, OutboundCall call) throws IOException {
        var version = bindings.resolve(exchangeId, CounterpartyRole.PROVIDER)
                .map(ExchangeBinding::version)
                .orElse(ProtocolVersion.NATIVE);
        return requester(version).pollStatus(exchangeId, call);
    }

    private ProtocolRequester requester(ProtocolVersion version) {
        var requester = byVersion.getOrDefault(version, byVersion.get(ProtocolVersion.NATIVE));
        if (requester == null) {
            throw new IllegalStateException("No certificate requester registered for protocol version " + version);
        }
        return requester;
    }
}
