package org.metaform.certo.consumer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code POST /management/v1/consumer/exchanges/query} — the consumer-side reconciliation query.
 * {@code awaitingAcceptanceOnly} (default {@code true}) narrows to exchanges that are {@code FULFILLED} but
 * not yet accepted — the outstanding work a client needs to drive (retrieve + accept).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsumerExchangeQuery(Boolean awaitingAcceptanceOnly) {
}
