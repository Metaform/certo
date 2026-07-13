package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;
import org.metaform.certo.protocol.ExchangeBinding;

/**
 * Outbound port for provider &rarr; consumer notifications. The {@code @Primary} implementation
 * ({@code DispatchingConsumerNotifier}) routes by protocol version to per-version adapters:
 * v3 {@code Ccm300Notifier}, v2.4.0 {@code Ccm240Notifier}.
 */
public interface ConsumerNotifier {

    /**
     * Notifies a specific {@code target} consumer of a certificate lifecycle event — a provider-initiated
     * push is always explicitly addressed (there is no provider-side "who holds this" registry; interest
     * lives on the consumer). The {@code target}'s {@code version} selects the adapter and its
     * {@code callbackUrl} the endpoint (a native-version target uses the configured consumer URL). Returns
     * {@code true} on successful delivery.
     */
    boolean notifyLifecycle(ExchangeBinding target, LifecycleStatusData data);

    /** Pushes a fulfillment status to the consumer (routed by the exchange's binding). Returns {@code true} on success. */
    boolean notifyFulfillment(FulfillmentStatusData data);
}
