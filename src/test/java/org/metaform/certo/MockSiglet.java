package org.metaform.certo;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.metaform.certo.common.security.ResolvedToken;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.web.ApiException;

import java.time.Instant;
import java.util.Date;

/**
 * In-JVM mock of a siglet STS for tests: it fulfils both halves of the siglet contract without HTTP —
 * {@link #resolve} returns an outbound token (audience = the counterparty) plus the counterparty
 * {@code endpoint}, and {@link #verify} verifies an inbound token's signature/expiry and resolves its
 * audience DID to a participant context. Wired as the {@code @Primary} {@code SecurityTokenSource} /
 * {@code SecurityTokenVerifier} by {@link MockSigletConfig}. The outbound endpoint is settable — real-server
 * tests point it at their own loopback URL.
 */
public class MockSiglet {

    private final OctetKeyPair key;
    private final ParticipantContextStore contexts;
    private volatile String endpoint;

    public MockSiglet(ParticipantContextStore contexts, String endpoint) {
        this.contexts = contexts;
        this.endpoint = endpoint;
        try {
            this.key = new OctetKeyPairGenerator(Curve.Ed25519).keyID("mock-siglet").generate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the mock siglet key", e);
        }
    }

    /** The counterparty endpoint the cache returns for outbound calls (the loopback app URL in real-server tests). */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Mints a token addressed to {@code aud} (receiver DID) from {@code sub} (sender DID), carrying a {@code bpn} claim. */
    public String mint(String aud, String sub, String bpn) {
        try {
            var builder = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .audience(aud)
                    .issuer("mock-siglet")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (bpn != null) {
                builder.claim("bpn", bpn);
            }
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.getKeyID()).build(), builder.build());
            jwt.sign(new Ed25519Signer(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint mock siglet token", e);
        }
    }

    /** {@code SecurityTokenSource}: the cached token (audience = the counterparty) + the counterparty endpoint. */
    public ResolvedToken resolve(String participantContextId, String counterpartyDid, String flowId) {
        var sender = contexts.find(participantContextId).orElse(null);
        var sub = sender != null ? sender.did() : participantContextId;
        var bpn = sender != null ? sender.bpn() : null;
        return new ResolvedToken(mint(counterpartyDid, sub, bpn), endpoint);
    }

    /** {@code SecurityTokenVerifier}: verify signature + expiry, resolve the audience DID to a participant context. */
    public VerifiedRequestContext verify(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw ApiException.unauthorized("Missing bearer token");
        }
        try {
            var jwt = SignedJWT.parse(bearerToken);
            if (!jwt.verify(new Ed25519Verifier(key.toPublicJWK()))) {
                throw ApiException.unauthorized("Invalid security token");
            }
            var claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() != null && claims.getExpirationTime().before(new Date())) {
                throw ApiException.unauthorized("Expired security token");
            }
            var audience = claims.getAudience() == null || claims.getAudience().isEmpty()
                    ? null : claims.getAudience().get(0);
            var context = contexts.findByDid(audience)
                    .orElseThrow(() -> ApiException.unauthorized("Token audience is not a known participant context"));
            return new VerifiedRequestContext(claims.getSubject(), context.participantContextId(), claims.getClaims());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.unauthorized("Invalid security token");
        }
    }
}
