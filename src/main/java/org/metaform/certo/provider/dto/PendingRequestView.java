package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A management view of one consumer-initiated exchange awaiting fulfillment, returned by the request
 * queries. {@code consumerBpn} is the field the client maps to a live {@code flow_id} to notify the
 * consumer; the type/locations let it match against an issued certificate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingRequestView(
        String exchangeId,
        String consumerBpn,
        String certificateType,
        List<String> certifiedLocations,
        OffsetDateTime requestedAt,
        FulfillmentStatus status) {
}
