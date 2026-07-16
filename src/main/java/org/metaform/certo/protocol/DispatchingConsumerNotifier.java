package org.metaform.certo.protocol;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing provider&rarr;consumer notifier: it derives the counterparty's protocol version from the
 * exchange's {@link ExchangeBinding} — supplied by the caller for a lifecycle push, looked up from the
 * store for fulfillment — then delegates to that version's {@link ProtocolNotifier}.
 * No binding ⇒ {@link ProtocolVersion#NATIVE}. This is the single place version selection happens for
 * this direction; the core service depends only on {@link ConsumerNotifier} and is unaware of versions.
 */
@Component
@Primary
public class DispatchingConsumerNotifier implements ConsumerNotifier {

    private final Map<ProtocolVersion, ProtocolNotifier> byVersion;
    private final ExchangeBindingStore bindings;

    public DispatchingConsumerNotifier(List<ProtocolNotifier> notifiers, ExchangeBindingStore bindings) {
        this.byVersion = notifiers.stream().collect(Collectors.toMap(ProtocolNotifier::version, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public boolean notifyLifecycle(ExchangeBinding target, LifecycleStatusData data, OutboundCall call) {
        return adapter(target).notifyLifecycle(target, data, call);
    }

    @Override
    public boolean notifyFulfillment(FulfillmentStatusData data, OutboundCall call) {
        var binding = bindings.resolve(data.exchangeId(), CounterpartyRole.CONSUMER).orElse(null);
        return adapter(binding).notifyFulfillment(binding, data, call);
    }

    private ProtocolNotifier adapter(ExchangeBinding binding) {
        var version = binding != null ? binding.version() : ProtocolVersion.NATIVE;
        var notifier = byVersion.get(version);
        return notifier != null ? notifier : byVersion.get(ProtocolVersion.NATIVE);
    }
}
