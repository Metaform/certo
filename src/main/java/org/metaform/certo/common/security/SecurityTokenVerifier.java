package org.metaform.certo.common.security;

/**
 * Verifies an inbound CCM security token via the configured token backend: the in-process backend checks the
 * signature against its local Ed25519 key, the siglet backend delegates to siglet's revocation-aware
 * {@code POST /tokens/verify}. Either way the token's audience (a tenant DID) resolves to the receiving
 * {@link org.metaform.certo.common.pc.ParticipantContext}.
 */
public interface SecurityTokenVerifier {

    /**
     * Verifies the bearer token (signature and expiry via the configured backend) and resolves its audience to
     * the receiving tenant, returning the authenticated caller.
     *
     * @param bearerToken the raw JWT presented on the request (no {@code Bearer } prefix)
     * @return the verified caller identity
     * @throws org.metaform.certo.common.web.ApiException 401 if the token is missing, malformed, or invalid
     */
    VerifiedRequestContext verify(String bearerToken);
}
