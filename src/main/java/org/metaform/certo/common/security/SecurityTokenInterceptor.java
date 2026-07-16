package org.metaform.certo.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.metaform.certo.common.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces a verified token on inbound CCM protocol calls (both versions). Registered only on the protocol
 * paths (never {@code /management/**}) by {@link SecurityWebConfig}. Security is always on. A verified
 * request whose token audience resolves to a known participant context is required — the verifier rejects
 * anything else with 401 — so downstream handlers can trust a non-null {@link VerifiedRequestContext} with a
 * resolved participant. The verified context is stashed as a request attribute for downstream use.
 */
@Component
public class SecurityTokenInterceptor implements HandlerInterceptor {

    /** Request attribute holding the {@link VerifiedRequestContext} once a call has passed verification. */
    public static final String VERIFIED_ATTRIBUTE = "certo.verifiedRequestContext";

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityTokenVerifier verifier;

    public SecurityTokenInterceptor(SecurityTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw ApiException.unauthorized("Missing or malformed Authorization: Bearer header");
        }
        var identity = verifier.verify(header.substring(BEARER_PREFIX.length()).trim());
        request.setAttribute(VERIFIED_ATTRIBUTE, identity);
        return true;
    }
}
