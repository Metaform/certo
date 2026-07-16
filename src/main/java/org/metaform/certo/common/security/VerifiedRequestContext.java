package org.metaform.certo.common.security;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

/**
 * The verified security context of an inbound CCM protocol call, established by verifying the presented
 * token. It binds together the two parties of the call: the <b>counterparty</b> that made it —
 * {@code subject} is the caller's DID (the token {@code sub}), and {@link #bpnOrSubject()} its BPN — and
 * the <b>receiving tenant</b> it was addressed to — {@code participantContextId}, resolved from the token
 * audience (its DID). {@code claims} carries the remaining verified claims (e.g. flow/agreement context).
 * (Named for the request, not just the caller, precisely because it also carries the receiving tenant.)
 */
public record VerifiedRequestContext(@NotNull String subject,
                                     @NotNull String participantContextId,
                                     Map<String, Object> claims) {

    public VerifiedRequestContext {
        // A verified call always has a caller (sub) and a resolved receiving tenant; enforce it at
        // construction so no downstream code has to null-check either.
        Objects.requireNonNull(subject, "subject (token sub) must be present on a verified request");
        Objects.requireNonNull(participantContextId, "participantContextId must be resolved for a verified request");
    }

    /**
     * The caller's BPN — a {@code bpn} claim if the token carries one, otherwise its subject (DID).
     */
    public String bpnOrSubject() {
        if (claims != null && claims.get("bpn") instanceof String bpn && !bpn.isBlank()) {
            return bpn;
        }
        return subject;
    }
}
