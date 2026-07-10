package org.metaform.certo.protocol;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.provider.spi.ConsumerNotifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing provider&rarr;consumer notifier: it looks up the exchange's {@link ExchangeBinding} to
 * find the counterparty's protocol version, then delegates to that version's {@link ProtocolNotifier}.
 * No binding ⇒ {@link ProtocolVersions#NATIVE}. This is the single place version selection happens for
 * this direction; the core service depends only on {@link ConsumerNotifier} and is unaware of versions.
 */
@Component
@Primary
public class DispatchingConsumerNotifier implements ConsumerNotifier {

    private final Map<String, ProtocolNotifier> byVersion;
    private final ExchangeBindingStore bindings;

    public DispatchingConsumerNotifier(List<ProtocolNotifier> notifiers, ExchangeBindingStore bindings) {
        this.byVersion = notifiers.stream().collect(Collectors.toMap(ProtocolNotifier::version, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public boolean notifyLifecycle(LifecycleStatusData data) {
        var certificateId = data.certificate() == null ? null : data.certificate().certificateId();
        var binding = bindings.resolve(data.exchangeId(), certificateId, CounterpartyRole.CONSUMER).orElse(null);
        return adapter(binding).notifyLifecycle(binding, data);
    }

    @Override
    public boolean notifyFulfillment(FulfillmentStatusData data) {
        var binding = bindings.resolve(data.exchangeId(), data.certificateId(), CounterpartyRole.CONSUMER).orElse(null);
        return adapter(binding).notifyFulfillment(binding, data);
    }

    private ProtocolNotifier adapter(ExchangeBinding binding) {
        var version = binding != null ? binding.version() : ProtocolVersions.NATIVE;
        var notifier = byVersion.get(version);
        return notifier != null ? notifier : byVersion.get(ProtocolVersions.NATIVE);
    }
}
