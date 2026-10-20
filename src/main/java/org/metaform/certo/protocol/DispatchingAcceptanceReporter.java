package org.metaform.certo.protocol;

import org.metaform.certo.protocol.spi.ProtocolAcceptanceReporter;
import org.metaform.certo.protocol.store.ExchangeBindingStore;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.consumer.spi.AcceptanceReporter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing consumer&rarr;provider acceptance reporter: it looks up the exchange's
 * {@link org.metaform.certo.protocol.domain.ExchangeBinding} to find the counterparty's protocol, then
 * delegates to that protocol's {@link ProtocolAcceptanceReporter}. Every exchange has a binding (recorded
 * when it was opened); a missing one is a data-integrity error, not a fallback. The core service depends
 * only on {@link AcceptanceReporter} and is unaware of protocols.
 */
@Component
@Primary
public class DispatchingAcceptanceReporter implements AcceptanceReporter {

    private final Map<String, ProtocolAcceptanceReporter> byId;
    private final ExchangeBindingStore bindings;

    public DispatchingAcceptanceReporter(List<ProtocolAcceptanceReporter> reporters, ExchangeBindingStore bindings) {
        this.byId = reporters.stream().collect(Collectors.toMap(ProtocolAcceptanceReporter::protocolId, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public boolean report(String exchangeId,
                          AcceptanceStatus status,
                          OutboundCall call,
                          List<StatusError> errors) {
        var binding = bindings.resolve(exchangeId, CounterpartyRole.PROVIDER)
                .orElseThrow(() -> new IllegalStateException(
                        "No protocol binding recorded for exchange " + exchangeId));
        var reporter = byId.get(binding.protocolId());
        if (reporter == null) {
            throw new IllegalStateException("No acceptance reporter registered for protocol " + binding.protocolId());
        }
        return reporter.report(binding, exchangeId, status, errors, call);
    }
}
