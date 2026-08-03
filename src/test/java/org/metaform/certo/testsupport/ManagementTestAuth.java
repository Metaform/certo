package org.metaform.certo.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Date;

/**
 * Test support for the OAuth2-protected management API ({@code /management/**}): a {@link JwtDecoder}
 * that accepts any well-formed JWT <em>without</em> signature verification and hands its claims to the
 * standard authorities mapping ({@code scope} claim &rarr; {@code SCOPE_&hellip;}), so tests exercise the real
 * filter chain and scope authorization without an IdP. Authorization decisions stay real: a token
 * without the required scope is still 403, and a missing/unparseable token is still 401.
 *
 * <p>{@link #token(String...)} mints a token carrying the given scopes; {@link #adminToken()} the
 * superseding {@code certo-mgmt-api:admin}. {@link MockSigletConfig} imports this configuration, and
 * siglet-minted test tokens also carry the admin scope, so a single default bearer serves both the
 * protocol and management surfaces in MockMvc tests.
 */
@TestConfiguration
public class ManagementTestAuth {

    /** HMAC key for minted test tokens — never verified by the lenient decoder, only needed to sign. */
    private static final byte[] SIGNING_KEY = new byte[32];

    @Bean
    JwtDecoder managementTestJwtDecoder() {
        return ManagementTestAuth::parseUnverified;
    }

    /** Mints a management-API bearer carrying the given scope strings (e.g. {@code certo-mgmt-api:provider:read}). */
    public static String token(String... scopes) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject("mgmt-test-client")
                    .issuer("mock-idp")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("scope", String.join(" ", scopes))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint management test token", e);
        }
    }

    /** A token with only {@code certo-mgmt-api:admin} — must supersede every fine-grained scope. */
    public static String adminToken() {
        return token("certo-mgmt-api:admin");
    }

    private static Jwt parseUnverified(String token) {
        try {
            var jwt = SignedJWT.parse(token);
            var builder = Jwt.withTokenValue(token)
                    .headers(headers -> headers.putAll(jwt.getHeader().toJSONObject()));
            jwt.getJWTClaimsSet().getClaims().forEach((name, value) ->
                    builder.claim(name, value instanceof Date date ? date.toInstant() : value));
            return builder.build();
        } catch (Exception e) {
            throw new BadJwtException("Malformed management test token", e);
        }
    }
}
