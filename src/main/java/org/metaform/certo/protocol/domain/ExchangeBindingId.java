package org.metaform.certo.protocol.domain;

import org.metaform.certo.protocol.CounterpartyRole;

import java.io.Serializable;

/**
 * Composite key of an {@link ExchangeBinding}: one binding per (exchange, counterparty role). The role is
 * part of the key because each party records its <b>own</b> view of the exchange's counterparty — in a
 * deployment where one runtime plays both roles (or shares a database), the provider's binding (role
 * {@code CONSUMER}) and the consumer's binding (role {@code PROVIDER}) for the same exchange coexist.
 */
public record ExchangeBindingId(String exchangeId, CounterpartyRole role) implements Serializable {
}
