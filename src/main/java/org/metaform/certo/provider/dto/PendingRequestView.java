package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LocationFilter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A management view of one consumer-initiated exchange awaiting fulfillment, returned by the request
 * queries. {@code counterpartyId} is the consumer the client maps to a live {@code flow_id} to notify;
 * the type/locations let it match against an issued certificate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingRequestView(
        String exchangeId,
        String counterpartyId,
        String certificateType,
        List<LocationFilter> certifiedLocations,
        OffsetDateTime requestedAt,
        FulfillmentStatus status) {
}
