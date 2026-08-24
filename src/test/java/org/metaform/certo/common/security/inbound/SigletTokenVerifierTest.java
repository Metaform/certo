package org.metaform.certo.common.security.inbound;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metaform.certo.common.http.HttpClientProperties;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.metaform.certo.common.security.SecurityProperties;
import org.metaform.certo.common.security.exchange.TokenExchangeClient;
import org.metaform.certo.testsupport.MockSiglet;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SigletTokenVerifier} over real HTTP: the verify call, and the token exchange that authenticates it
 * — whose {@code resource} is <em>configured</em>, because verification is not bound to a participant
 * context (the tenant is only known once siglet has answered).
 */
class SigletTokenVerifierTest {

    private static final String PROVIDER_DID = "did:web:provider";
    private static final String CONSUMER_DID = "did:web:consumer";
    private static final String CONSUMER_BPN = "BPNL0000000002CD";

    @TempDir
    Path tempDir;

    private MockWebServer siglet;
    private MockWebServer broker;
    private RetryingHttpClient http;
    private InMemoryContexts contexts;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        siglet = new MockWebServer();
        siglet.start();
        broker = new MockWebServer();
        broker.start();
        http = new RetryingHttpClient(new OkHttpClient(), new HttpClientProperties(2, 2, 2, 5, 0, 1L, 5L));
        contexts = new InMemoryContexts();
        contexts.save(new ParticipantContext("provider-context", "BPNL0000000001AB",
                "urn:bpn:BPNL0000000001AB", PROVIDER_DID));
        // Only the local `aud` read parses this token; siglet (here, the mock server) is the signature authority.
        token = new MockSiglet(contexts, "http://counterparty").mint(PROVIDER_DID, CONSUMER_DID, CONSUMER_BPN);
    }

    @AfterEach
    void tearDown() throws Exception {
        siglet.shutdown();
        broker.shutdown();
    }

    private SigletTokenVerifier verifier(SecurityProperties.TokenExchange exchange) {
        var mapper = JsonMapper.builder().build();
        var properties = new SecurityProperties(siglet.url("/").toString(), exchange);
        return new SigletTokenVerifier(properties, contexts, http, mapper,
                new TokenExchangeClient(properties, http, mapper));
    }

    private void enqueueVerifiedClaims() {
        siglet.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"aud\":\"" + PROVIDER_DID + "\",\"sub\":\"" + CONSUMER_DID
                        + "\",\"bpn\":\"" + CONSUMER_BPN + "\"}"));
    }

    @Test
    void exchangeDisabled_callsSigletUnauthenticated() throws Exception {
        enqueueVerifiedClaims();

        var verified = verifier(new SecurityProperties.TokenExchange(false, null, null, null, null, null))
                .verify(token);

        assertThat(verified.participantContextId()).isEqualTo("provider-context");
        assertThat(verified.subject()).isEqualTo(CONSUMER_DID);
        var request = siglet.takeRequest();
        assertThat(request.getPath()).isEqualTo("/tokens/verify");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(broker.getRequestCount()).isZero();
    }

    @Test
    void exchangeEnabled_exchangesForTheConfiguredResource_thenAuthenticatesTheVerifyCall() throws Exception {
        var subjectToken = tempDir.resolve("token");
        Files.writeString(subjectToken, "k8s.sa.jwt");
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"exchanged.jwt\"}"));
        enqueueVerifiedClaims();

        var exchange = new SecurityProperties.TokenExchange(true, broker.url("/token").toString(),
                "siglet:verify", "did:web:siglet", "verify-context", subjectToken.toString());
        var verified = verifier(exchange).verify(token);

        assertThat(verified.participantContextId()).isEqualTo("provider-context");
        assertThat(verified.bpn()).isEqualTo(CONSUMER_BPN);
        // Not a participant context id: verification has no tenant until siglet answers.
        var exchangeBody = broker.takeRequest().getBody().readUtf8();
        assertThat(HttpUrl.parse("http://form/?" + exchangeBody).queryParameter("resource"))
                .isEqualTo("verify-context");
        assertThat(siglet.takeRequest().getHeader("Authorization")).isEqualTo("Bearer exchanged.jwt");
    }

    /** Minimal store stand-in — Mockito is excluded from this build. */
    private static final class InMemoryContexts implements ParticipantContextStore {

        private final Map<String, ParticipantContext> byId = new LinkedHashMap<>();

        @Override
        public void save(ParticipantContext context) {
            byId.put(context.participantContextId(), context);
        }

        @Override
        public Optional<ParticipantContext> find(String participantContextId) {
            return Optional.ofNullable(byId.get(participantContextId));
        }

        @Override
        public boolean exists(String participantContextId) {
            return byId.containsKey(participantContextId);
        }

        @Override
        public Optional<ParticipantContext> findByDid(String did) {
            return byId.values().stream().filter(c -> c.did().equals(did)).findFirst();
        }

        @Override
        public boolean existsByDid(String did) {
            return findByDid(did).isPresent();
        }

        @Override
        public Collection<ParticipantContext> all() {
            return byId.values();
        }

        @Override
        public void delete(String participantContextId) {
            byId.remove(participantContextId);
        }
    }
}
