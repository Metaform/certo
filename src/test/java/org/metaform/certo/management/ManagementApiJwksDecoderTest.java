package org.metaform.certo.management;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JwkSetJwtDecoder} end to end against a real <b>Ed25519</b> JWKS ({@code jwk-set-uri} set, no
 * issuer discovery) — the jwtlet/siglet setup that no Spring-built decoder can verify. Signature
 * verification is real here (unlike the lenient {@code ManagementTestAuth} decoder): a token from a
 * rogue key with the same {@code kid} is rejected, as are a wrong issuer, an expired token, and an
 * insufficient scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagementApiJwksDecoderTest {

    private static final String ISSUER = "http://mgmt-idp.test";
    private static final OctetKeyPair KEY;
    private static final OctetKeyPair ROGUE_KEY;
    private static final MockWebServer JWKS_SERVER;

    static {
        try {
            KEY = new OctetKeyPairGenerator(Curve.Ed25519).keyID("mgmt-idp-signing").generate();
            // Same kid, different key material: only the signature check can tell them apart.
            ROGUE_KEY = new OctetKeyPairGenerator(Curve.Ed25519).keyID("mgmt-idp-signing").generate();
            JWKS_SERVER = new MockWebServer();
            JWKS_SERVER.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(KEY.toPublicJWK()).toString());
                }
            });
            JWKS_SERVER.start();
        } catch (Exception e) {
            throw new IllegalStateException("Could not set up the mock JWKS endpoint", e);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> JWKS_SERVER.url("/.well-known/jwks.json").toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    }

    @AfterAll
    static void shutdown() throws Exception {
        JWKS_SERVER.shutdown();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void edDsaToken_fromJwks_isAccepted() throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer " + mint(KEY, ISSUER, "certo-mgmt-api:read")))
                .andExpect(status().isOk());
    }

    @Test
    void tokenFromRogueKey_isRejected() throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer " + mint(ROGUE_KEY, ISSUER, "certo-mgmt-api:read")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuer_isRejected() throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer " + mint(KEY, "http://someone-else.test", "certo-mgmt-api:read")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer " + mint(KEY, ISSUER, "certo-mgmt-api:read", -60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void insufficientScope_is403() throws Exception {
        // provider scopes do not reach the participant surface (and write-includes-read makes any
        // action-wide scope sufficient for reads, so a foreign resource scope is the 403 case)
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer " + mint(KEY, ISSUER, "certo-mgmt-api:provider:read")))
                .andExpect(status().isForbidden());
    }

    private static String mint(OctetKeyPair key, String issuer, String scope) throws Exception {
        return mint(key, issuer, scope, 300);
    }

    private static String mint(OctetKeyPair key, String issuer, String scope, long expiresInSeconds) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject("mgmt-client")
                .issuer(issuer)
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds)))
                .claim("scope", scope)
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new Ed25519Signer(key));
        return jwt.serialize();
    }
}
