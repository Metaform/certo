package org.metaform.certo.consumer.dto;

import java.util.List;

/** A page of consumer-side exchange views returned by the reconciliation query. */
public record ConsumerExchangePage(List<ConsumerExchangeView> items) {
}
