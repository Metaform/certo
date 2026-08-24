package org.metaform.certo.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the CCM protocol security-token layer. Security is <b>always on</b> and tokens always
 * come from a <b>siglet</b> STS: every inbound protocol call is verified via its {@code POST /tokens/verify}
 * and every outbound call resolves its token <b>and endpoint</b> from the siglet cache. A deployment must
 * point at a siglet — {@code certo.security.siglet-base-url} is required (dev/test point at a mock siglet).
 *
 * <p>Participant context and audience are per-tenant, not configured here: the outbound participant context
 * id travels on each {@link OutboundCall}, and the inbound audience is the token's own {@code aud} (a tenant
 * DID), resolved to a participant context after verification.
 *
 * @param sigletBaseUrl base URL of the siglet STS ({@code /tokens/verify}, {@code /tokens/{pcid}/{flowId}})
 * @param tokenExchange how calls to that siglet authenticate themselves; off by default
 */
@ConfigurationProperties(prefix = "certo.security")
public record SecurityProperties(String sigletBaseUrl, @DefaultValue TokenExchange tokenExchange) {

    /**
     * RFC 8693 token exchange against the siglet's token broker. When {@code enabled}, every call this
     * runtime makes to siglet first exchanges the pod's Kubernetes ServiceAccount token for a short-lived
     * access token and sends it as the bearer; when disabled the siglet calls stay unauthenticated.
     *
     * <p>The exchange's {@code resource} names the participant context the token is requested for. Outbound
     * calls are per-tenant so they pass their own {@code participantContextId}; the inbound verification call
     * is not bound to any tenant, so it uses the configured {@code verifyResource}.
     *
     * @param enabled          whether siglet calls authenticate at all
     * @param url              the broker's token endpoint (e.g. {@code https://jwtlet:8080/token})
     * @param scope            space-separated scopes requested for the exchanged token
     * @param audience         the {@code aud} requested for the exchanged token (siglet)
     * @param verifyResource   the {@code resource} used by the tenant-independent {@code /tokens/verify} call
     * @param subjectTokenPath file holding the Kubernetes ServiceAccount JWT sent as {@code subject_token};
     *                         re-read on every exchange because the kubelet rotates a projected token in place
     */
    public record TokenExchange(@DefaultValue("false") boolean enabled,
                                String url,
                                String scope,
                                String audience,
                                String verifyResource,
                                @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token")
                                String subjectTokenPath) {
    }
}
