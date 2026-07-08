package org.metaform.certo.provider.client;

import org.metaform.certo.common.model.FulfillmentStatusData;
import org.metaform.certo.common.model.LifecycleStatusData;

/**
 * Outbound port for provider &rarr; consumer notifications. The default implementation
 * ({@link ConsumerNotificationClient}) sends v3 CloudEvents; the backward-compat adapter provides a
 * routing implementation that redirects to a legacy v2.4.0 consumer when the recipient is a legacy peer.
 */
public interface ConsumerNotifier {

    /** Notifies the consumer of a certificate lifecycle event. Returns {@code true} on successful delivery. */
    boolean notifyLifecycle(LifecycleStatusData data);

    /** Pushes a fulfillment status to the consumer. Returns {@code true} on successful delivery. */
    boolean notifyFulfillment(FulfillmentStatusData data);
}
