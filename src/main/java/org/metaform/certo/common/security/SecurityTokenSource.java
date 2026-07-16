package org.metaform.certo.common.security;

/**
 * Resolves the token and endpoint for an outbound CCM call. The token is scoped to the counterparty
 * (audience = the counterparty's DID) and issued on behalf of the sender's participant context.
 */
public interface SecurityTokenSource {

    /**
     * @param participantContextId the sender tenant (the siglet cache key prefix)
     * @param counterpartyDid      the receiver's DID (informational — the cached token already carries the
     *                             correct audience)
     * @param flowId               the live outbound transfer/flow id (the siglet cache key)
     * @return the token to present and the counterparty {@code endpointUrl} to call
     * @throws org.metaform.certo.common.web.ApiException if a token cannot be resolved
     */
    ResolvedToken resolve(String participantContextId, String counterpartyDid, String flowId);
}
