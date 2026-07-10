package org.metaform.certo.provider.spi;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;

/**
 * Outbound port for provider &rarr; consumer notifications. The {@code @Primary} implementation
 * ({@code DispatchingConsumerNotifier}) routes by protocol version to per-version adapters:
 * v3 {@code Ccm300Notifier}, v2.4.0 {@code Ccm240Notifier}.
 */
public interface ConsumerNotifier {

    /** Notifies the consumer of a certificate lifecycle event. Returns {@code true} on successful delivery. */
    boolean notifyLifecycle(LifecycleStatusData data);

    /** Pushes a fulfillment status to the consumer. Returns {@code true} on successful delivery. */
    boolean notifyFulfillment(FulfillmentStatusData data);
}
