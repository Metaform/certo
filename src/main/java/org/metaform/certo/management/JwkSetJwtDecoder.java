package org.metaform.certo.management;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * A JWKS-backed {@link JwtDecoder} that — unlike anything Spring Security can be configured to build —
 * verifies <b>EdDSA/Ed25519</b> signatures, the algorithm the jwtlet/siglet family signs with. Spring's
 * own decoders are limited twice over: its {@code SignatureAlgorithm} enum has no EdDSA entry (so
 * neither the {@code jws-algorithms} property nor issuer-discovery algorithm inference can express it),
 * and Nimbus's stock {@code JWSVerificationKeySelector} converts JWKS keys to {@code java.security.Key},
 * silently dropping OKP keys in the process. This decoder therefore verifies against the JWK directly
 * ({@link Ed25519Verifier} for OKP, plus RSA/EC for completeness) and never converts.
 *
 * <p>Everything else matches standard resource-server behavior: keys come from the JWKS endpoint
 * (cached/rate-limited via {@link JWKSourceBuilder} defaults, key selection by {@code kid}/alg), and
 * the caller-supplied validator enforces the usual claims ({@code exp}/{@code nbf}, {@code iss},
 * optionally {@code aud}).
 */
public class JwkSetJwtDecoder implements JwtDecoder {

    private final JWKSource<SecurityContext> jwkSource;
    private final Set<JWSAlgorithm> allowedAlgorithms;
    private final OAuth2TokenValidator<Jwt> validator;

    public JwkSetJwtDecoder(String jwkSetUri, Set<JWSAlgorithm> allowedAlgorithms, OAuth2TokenValidator<Jwt> validator) {
        try {
            this.jwkSource = JWKSourceBuilder.<SecurityContext>create(URI.create(jwkSetUri).toURL()).build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWKS URI: " + jwkSetUri, e);
        }
        this.allowedAlgorithms = Set.copyOf(allowedAlgorithms);
        this.validator = validator;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (Exception e) {
            throw new BadJwtException("Malformed token", e);
        }
        var algorithm = jwt.getHeader().getAlgorithm();
        if (!allowedAlgorithms.contains(algorithm)) {
            throw new BadJwtException("Unsupported JWS algorithm: " + algorithm);
        }
        verifySignature(jwt);
        var springJwt = toSpringJwt(jwt, token);
        var result = validator.validate(springJwt);
        if (result.hasErrors()) {
            var descriptions = result.getErrors().stream()
                    .map(error -> error.getDescription() == null ? error.getErrorCode() : error.getDescription())
                    .toList();
            throw new JwtValidationException("Invalid token: " + String.join("; ", descriptions), result.getErrors());
        }
        return springJwt;
    }

    private void verifySignature(SignedJWT jwt) {
        List<JWK> candidates;
        try {
            candidates = jwkSource.get(new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader())), null);
        } catch (Exception e) {
            // The JWKS endpoint being unavailable is a server-side problem, not a bad token.
            throw new JwtException("Could not retrieve JWKS to verify the token", e);
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new BadJwtException("No JWKS key matches the token (kid/alg)");
        }
        for (var jwk : candidates) {
            try {
                if (jwt.verify(verifierFor(jwk))) {
                    return;
                }
            } catch (JOSEException e) {
                // try the next candidate; all failing ends in the BadJwtException below
            }
        }
        throw new BadJwtException("Token signature verification failed");
    }

    private static JWSVerifier verifierFor(JWK jwk) throws JOSEException {
        return switch (jwk) {
            case OctetKeyPair okp -> new Ed25519Verifier(okp.toPublicJWK());
            case RSAKey rsa -> new RSASSAVerifier(rsa);
            case ECKey ec -> new ECDSAVerifier(ec);
            default -> throw new JOSEException("Unsupported JWK type: " + jwk.getKeyType());
        };
    }

    private static Jwt toSpringJwt(SignedJWT jwt, String token) {
        try {
            var builder = Jwt.withTokenValue(token)
                    .headers(headers -> headers.putAll(jwt.getHeader().toJSONObject()));
            jwt.getJWTClaimsSet().getClaims().forEach((name, value) ->
                    builder.claim(name, value instanceof Date date ? date.toInstant() : value));
            return builder.build();
        } catch (Exception e) {
            throw new BadJwtException("Could not read token claims", e);
        }
    }
}
