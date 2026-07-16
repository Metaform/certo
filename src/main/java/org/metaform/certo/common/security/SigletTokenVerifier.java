package org.metaform.certo.common.security;

import com.nimbusds.jwt.SignedJWT;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Verifies inbound CCM tokens by calling this runtime's siglet <b>verification endpoint</b>
 * ({@code POST /tokens/verify}) rather than checking signatures locally. Siglet authoritatively checks the
 * token's signature, expiry, <b>and revocation</b> (it looks up the {@code jti} in its renewable-token
 * store), so a token whose flow has been terminated is rejected before it naturally expires — the reason to
 * verify server-side instead of against the JWKS. On success siglet echoes the token's claims, from which
 * the audience DID resolves to the receiving {@link org.metaform.certo.common.pc.ParticipantContext}.
 * Siglet is the only token backend.
 *
 * <p>The endpoint requires an {@code audience} in the request; it is read from the (unverified) token
 * locally — the local read only names the tenant DID to check against, while siglet remains the authority on
 * the signature. This runtime does not send a caller JWT, matching {@link SigletTokenSource}'s assumption
 * that siglet's token-API auth is disabled for this deployment.
 */
@Component
public class SigletTokenVerifier implements SecurityTokenVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(SigletTokenVerifier.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final ParticipantContextStore contexts;
    private final String verifyUrl;

    public SigletTokenVerifier(SecurityProperties properties, ParticipantContextStore contexts,
                               OkHttpClient http, ObjectMapper mapper) {
        this.contexts = contexts;
        this.http = http;
        this.mapper = mapper;
        var base = properties.sigletBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("certo.security.siglet-base-url must be set for the siglet backend");
        }
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.verifyUrl = trimmed + "/tokens/verify";
    }

    @Override
    public VerifiedRequestContext verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw ApiException.unauthorized("Missing bearer token");
        }
        var audience = audienceOf(bearerToken);
        var claims = validateWithSiglet(bearerToken, audience);
        var verifiedAudience = firstAudience(claims);
        var context = contexts.findByDid(verifiedAudience)
                .orElseThrow(() -> ApiException.unauthorized("Token audience is not a known participant context"));
        var subject = claims.get("sub") instanceof String s && !s.isBlank() ? s : null;
        if (subject == null) {
            throw ApiException.unauthorized("Token is missing a subject (sub)");
        }
        return new VerifiedRequestContext(subject, context.participantContextId(), claims);
    }

    /** Reads the token's audience locally (no signature check) to tell siglet which tenant DID it targets. */
    private static String audienceOf(String bearerToken) {
        try {
            var aud = SignedJWT.parse(bearerToken).getJWTClaimsSet().getAudience();
            return aud == null || aud.isEmpty() ? null : aud.get(0);
        } catch (Exception e) {
            throw ApiException.unauthorized("Malformed security token");
        }
    }

    /** POSTs the token to siglet's verification endpoint; returns the echoed claims, or throws on rejection. */
    private Map<String, Object> validateWithSiglet(String token, String audience) {
        String requestBody;
        try {
            requestBody = mapper.writeValueAsString(new VerifyRequest(token, audience));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not serialize siglet verify request");
        }
        var request = new Request.Builder().url(verifyUrl)
                .post(RequestBody.create(requestBody, JSON)).build();
        try (var response = http.newCall(request).execute()) {
            if (response.code() == HttpStatus.UNAUTHORIZED.value()) {
                throw ApiException.unauthorized("Invalid security token: rejected by siglet (expired, revoked, or bad signature)");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Siglet verify returned HTTP " + response.code());
            }
            var body = response.body() == null ? "" : response.body().string();
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = mapper.readValue(body, Map.class);
            return claims;
        } catch (IOException e) {
            LOG.debug("Could not reach siglet for token verification: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach siglet for token verification: " + e.getMessage());
        }
    }

    private static String firstAudience(Map<String, Object> claims) {
        var aud = claims.get("aud");
        if (aud instanceof String s) {
            return s;
        }
        if (aud instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return s;
        }
        return null;
    }

    private record VerifyRequest(String token, String audience) {
    }
}
