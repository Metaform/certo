package org.metaform.certo.protocol;

/**
 * Which role the remote counterparty plays in an exchange — so outbound routing knows which direction a
 * binding applies to. Neutral metadata (not version-specific): a provider→consumer notification uses
 * bindings whose counterparty is a {@link #CONSUMER}, and a consumer→provider acceptance report uses
 * bindings whose counterparty is a {@link #PROVIDER}.
 */
public enum CounterpartyRole {
    CONSUMER,
    PROVIDER
}
