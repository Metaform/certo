package org.metaform.certo.common.security;

/**
 * A token resolved from the siglet cache for an outbound CCM call: the {@code bearerToken} to present and the
 * counterparty's {@code endpointUrl} to call. Both come from the cache — the endpoint travels with the token,
 * which is how a publish/fulfillment learns where to deliver.
 */
public record ResolvedToken(String bearerToken, String endpointUrl) {
}
