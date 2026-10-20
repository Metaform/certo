package org.metaform.certo.common.security.inbound;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

/**
 * The verified security context of an inbound protocol call, established by verifying the presented token.
 * It binds together the two parties of the call: the <b>counterparty</b> that made it — {@code subject} is
 * the caller's DID (the token {@code sub}; the one identity used for all internal correlation) and
 * {@code dataspaceIdentifier} its optional dataspace-assigned identifier — and the <b>receiving tenant</b>
 * it was addressed to — {@code participantContextId}, resolved from the token audience (its DID).
 * {@code claims} carries the remaining verified claims (e.g. flow/agreement context). (Named for the
 * request, not just the caller, precisely because it also carries the receiving tenant.)
 */
public record VerifiedRequestContext(@NotNull String subject,
                                     String dataspaceIdentifier,
                                     @NotNull String participantContextId,
                                     Map<String, Object> claims) {

    public VerifiedRequestContext {
        // A verified call always has a caller (sub) and a resolved receiving tenant; enforce it at
        // construction so no downstream code has to null-check either. The verifier rejects a token
        // missing sub with a 401 before ever constructing this.
        Objects.requireNonNull(subject, "subject (token sub) must be present on a verified request");
        Objects.requireNonNull(participantContextId, "participantContextId must be resolved for a verified request");
    }
}
