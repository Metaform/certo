package org.metaform.certo.protocol;

import org.metaform.certo.protocol.domain.ExchangeBinding;
import org.metaform.certo.protocol.spi.ProtocolNotifier;
import org.metaform.certo.protocol.store.ExchangeBindingStore;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing provider&rarr;consumer notifier: it derives the counterparty's protocol id from the
 * exchange's {@link ExchangeBinding} — supplied by the caller for a lifecycle push, looked up from the
 * store for fulfillment — then delegates to that protocol's {@link ProtocolNotifier}. Every exchange has
 * a binding (recorded when it was opened); a missing one is a data-integrity error, not a fallback. This
 * is the single place protocol selection happens for this direction; the core service depends only on
 * {@link ConsumerNotifier} and is unaware of protocols.
 */
@Component
@Primary
public class DispatchingConsumerNotifier implements ConsumerNotifier {

    private final Map<String, ProtocolNotifier> byId;
    private final ExchangeBindingStore bindings;

    public DispatchingConsumerNotifier(List<ProtocolNotifier> notifiers, ExchangeBindingStore bindings) {
        this.byId = notifiers.stream().collect(Collectors.toMap(ProtocolNotifier::protocolId, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public boolean notifyLifecycle(ExchangeBinding target, String messageId, LifecycleStatusData data, OutboundCall call) {
        return adapter(target).notifyLifecycle(target, messageId, data, call);
    }

    @Override
    public boolean notifyFulfillment(FulfillmentStatusData data, OutboundCall call) {
        var binding = bindings.resolve(data.exchangeId(), CounterpartyRole.CONSUMER)
                .orElseThrow(() -> new IllegalStateException(
                        "No protocol binding recorded for exchange " + data.exchangeId()));
        return adapter(binding).notifyFulfillment(binding, data, call);
    }

    private ProtocolNotifier adapter(ExchangeBinding binding) {
        var id = binding.protocolId();
        var notifier = byId.get(id);
        if (notifier == null) {
            throw new IllegalStateException("No notifier registered for protocol " + id);
        }
        return notifier;
    }
}
