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
 * End-to-end under security: a consumer-initiated pull for a held certificate, where every
 * consumer&rarr;provider hop (open request, fetch certificate, fetch documents, report acceptance) resolves
 * a token from the stubbed siglet cache and is verified by the runtime's own inbound interceptor via the same
 * siglet's verification endpoint ({@code POST /tokens/verify}). Both roles run in this one runtime; the stub
 * siglet returns the runtime's own URL as the token endpoint, so the calls loop back through the verified
 * protocol surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18089",
        "certo.security.siglet-base-url=http://localhost:18102"
})
class SecurityRoundTripTest {

    private static final String BASE = "http://localhost:18089";
    private static final OctetKeyPair SIGNING_KEY = generateKey();

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private MockWebServer siglet;

    @BeforeEach
    void startSiglet() throws Exception {
        siglet = new MockWebServer();
        var token = token();
        siglet.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                var path = request.getPath() == null ? "" : request.getPath();
                // Inbound verification (checked before the cache route, which shares the /tokens/ prefix).
                if (path.startsWith("/tokens/verify")) {
                    return verify(request.getBody().readUtf8());
                }
                if (path.startsWith("/tokens/")) {
                    return json("{\"token\":\"" + token + "\",\"endpoint\":\"" + BASE + "\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        siglet.start(18102);
    }

    @AfterEach
    void stopSiglet() throws Exception {
        siglet.shutdown();
    }

    @Test
    void consumerPull_underSecurity_completesTheAcceptanceLoop() throws Exception {
        // Open the request; then the client drives retrieve + accept. Every consumer->provider hop carries a
        // verified token resolved from the stub siglet cache.
        var initiate = post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/certificate-requests",
                "{\"providerBpn\":\"BPNL0000000001AB\",\"providerDid\":\"did:web:provider\","
                        + "\"certificateType\":\"ISO9001\",\"certifiedLocations\":[\"BPNS00000003AYRE\"],\"flowId\":\"flow-1\"}");
        assertThat(initiate.statusCode()).isEqualTo(202);
        var opened = mapper.readTree(initiate.body());
        var exchangeId = opened.get("exchangeId").asString();
        assertThat(opened.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");

        assertThat(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/" + exchangeId + "/retrieve?flowId=flow-1", "").statusCode())
                .isEqualTo(200);
        assertThat(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/" + exchangeId + "/accept",
                "{\"status\":\"ACCEPTED\",\"flowId\":\"flow-1\"}").statusCode()).isEqualTo(202);

        // Reconciliation query confirms the loop closed: the exchange is ACCEPTED, not awaiting acceptance.
        var page = mapper.readTree(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/query",
                "{\"awaitingAcceptanceOnly\":false}").body());
        var view = findItem(page, exchangeId);
        assertThat(view.get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    private static tools.jackson.databind.JsonNode findItem(tools.jackson.databind.JsonNode page, String exchangeId) {
        for (var item : page.get("items")) {
            if (exchangeId.equals(item.get("exchangeId").asString())) {
                return item;
            }
        }
        throw new AssertionError("exchange " + exchangeId + " not found in query result");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    /** Stubs siglet's server-side verification: validates the signature and echoes the token's claims. */
    private MockResponse verify(String requestBody) {
        try {
            var token = mapper.readTree(requestBody).get("token").asString();
            var jwt = SignedJWT.parse(token);
            if (!jwt.verify(new Ed25519Verifier(SIGNING_KEY.toPublicJWK()))) {
                return new MockResponse().setResponseCode(401);
            }
            return json(jwt.getJWTClaimsSet().toString());
        } catch (Exception e) {
            return new MockResponse().setResponseCode(401);
        }
    }

    private static String token() {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject("did:web:consumer.test")
                    .audience("did:web:provider")
                    .issuer("siglet")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(SIGNING_KEY.getKeyID()).build(), claims);
            jwt.sign(new Ed25519Signer(SIGNING_KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static OctetKeyPair generateKey() {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519).keyID("siglet-1").generate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
