package org.metaform.certo.common.event;

/**
 * Which side of a {@code Certificate Exchange} observed a status change.
 *
 * <p>A single Certo instance may act as both provider and consumer (the Verification Environment
 * deploys exactly one), so the same logical exchange produces events from both aggregates. Without
 * this discriminator the two are indistinguishable on the wire.
 */
public enum ExchangeRole {
    /** The provider's record ({@code ProviderCertificateExchange}) — authoritative for Fulfillment. */
    PROVIDER,
    /** The consumer's record ({@code ConsumerCertificateExchange}) — authoritative for Acceptance. */
    CONSUMER
}
