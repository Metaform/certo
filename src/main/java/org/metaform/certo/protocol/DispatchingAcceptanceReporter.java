package org.metaform.certo.protocol;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.consumer.client.AcceptanceReporter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The core-facing consumer&rarr;provider acceptance reporter: it looks up the exchange's
 * {@link ExchangeBinding} to find the counterparty's protocol version, then delegates to that version's
 * {@link ProtocolAcceptanceReporter}. No binding ⇒ {@link ProtocolVersions#NATIVE}. The core service
 * depends only on {@link AcceptanceReporter} and is unaware of versions.
 */
@Component
@Primary
public class DispatchingAcceptanceReporter implements AcceptanceReporter {

    private final Map<String, ProtocolAcceptanceReporter> byVersion;
    private final ExchangeBindingStore bindings;

    public DispatchingAcceptanceReporter(List<ProtocolAcceptanceReporter> reporters, ExchangeBindingStore bindings) {
        this.byVersion = reporters.stream().collect(Collectors.toMap(ProtocolAcceptanceReporter::version, Function.identity()));
        this.bindings = bindings;
    }

    @Override
    public void report(String exchangeId, String certificateId, AcceptanceStatus status, List<StatusError> errors) {
        var binding = bindings.resolve(exchangeId, certificateId, CounterpartyRole.PROVIDER).orElse(null);
        var version = binding != null ? binding.version() : ProtocolVersions.NATIVE;
        var reporter = byVersion.getOrDefault(version, byVersion.get(ProtocolVersions.NATIVE));
        reporter.report(binding, exchangeId, certificateId, status, errors);
    }
}
