package org.metaform.certo.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "certo.security")
public record SecurityProperties(String sigletBaseUrl) {
}
