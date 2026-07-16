package org.metaform.certo.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 inbound verification: the CCM protocol endpoints require a bearer token that is verified by calling the
 * runtime's siglet verification endpoint ({@code POST /tokens/verify}, stubbed on a fixed port), while the
 * management surface stays open. Tokens are Ed25519, minted here; the stub siglet plays siglet's server-side
 * role — it validates the signature (revocation-aware in production) and echoes the token's claims.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18085",
        "certo.security.siglet-base-url=http://localhost:18099"
})
class SecurityInboundTest {

    private static final String BASE = "http://localhost:18085";
    // The audience is a tenant DID resolved to a participant context; use the seeded provider tenant's DID.
    private static final String AUDIENCE = "did:web:provider";

    private static final OctetKeyPair SIGNING_KEY = generateKey("siglet-1");
    private static final OctetKeyPair UNKNOWN_KEY = generateKey("unknown-1");

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private MockWebServer siglet;

    @BeforeEach
    void startSiglet() throws Exception {
        siglet = new MockWebServer();
        siglet.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/tokens/verify")) {
                    return verify(request.getBody().readUtf8());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        siglet.start(18099);
    }

    /**
     * Stubs siglet's server-side verification: parses the request body's {@code token}, checks it was signed
     * by the known key (rejecting anything else with 401 as siglet would for a bad signature/expiry/revocation),
     * and on success echoes the token's claims as JSON.
     */
    private MockResponse verify(String requestBody) {
        try {
            var token = mapper.readTree(requestBody).get("token").asString();
            var jwt = SignedJWT.parse(token);
            if (!jwt.verify(new Ed25519Verifier(SIGNING_KEY.toPublicJWK()))) {
                return new MockResponse().setResponseCode(401);
            }
            return new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(jwt.getJWTClaimsSet().toString());
        } catch (Exception e) {
            return new MockResponse().setResponseCode(401);
        }
    }

    @AfterEach
    void stopSiglet() throws Exception {
        siglet.shutdown();
    }

    @Test
    void protocolCall_withoutToken_isUnauthorized() throws Exception {
        assertThat(post("/certificate-requests", REQUEST, null).statusCode()).isEqualTo(401);
    }

    @Test
    void protocolCall_withValidToken_passes() throws Exception {
        assertThat(post("/certificate-requests", REQUEST, token(SIGNING_KEY, AUDIENCE)).statusCode()).isEqualTo(202);
    }

    @Test
    void protocolCall_withTokenFromUnknownKey_isUnauthorized() throws Exception {
        assertThat(post("/certificate-requests", REQUEST, token(UNKNOWN_KEY, AUDIENCE)).statusCode()).isEqualTo(401);
    }

    @Test
    void protocolCall_withWrongAudience_isUnauthorized() throws Exception {
        assertThat(post("/certificate-requests", REQUEST, token(SIGNING_KEY, "did:web:someone-else")).statusCode())
                .isEqualTo(401);
    }

    @Test
    void managementCall_isNotTokenProtected() throws Exception {
        // No token, yet the management surface answers normally (never siglet-secured).
        assertThat(post("/management/v1/participant-contexts/pctx-seed-provider/certificate-requests/query", "{}", null).statusCode()).isEqualTo(200);
    }

    @Test
    void verifiedCaller_becomesExchangeCounterparty() throws Exception {
        // A request under a valid token opens a pending exchange whose counterparty is the verified caller
        // (the token subject), not the configured BPN.
        assertThat(post("/certificate-requests", "{\"certificateType\":\"P5-IDENTITY-TYPE\"}",
                token(SIGNING_KEY, AUDIENCE)).statusCode()).isEqualTo(202);

        var page = post("/management/v1/participant-contexts/pctx-seed-provider/certificate-requests/query",
                "{\"certificateType\":\"P5-IDENTITY-TYPE\"}", null);
        var items = mapper.readTree(page.body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("consumerBpn").asString()).isEqualTo("did:web:consumer.test");
    }

    private static final String REQUEST = "{\"certificateType\":\"SEC-TEST-TYPE\"}";

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(OctetKeyPair key, String audience) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject("did:web:consumer.test")
                    .audience(audience)
                    .issuer("siglet")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new Ed25519Signer(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static OctetKeyPair generateKey(String keyId) {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519).keyID(keyId).generate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
