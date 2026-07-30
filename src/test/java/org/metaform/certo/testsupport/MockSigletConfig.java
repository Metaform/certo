package org.metaform.certo.testsupport;

import org.metaform.certo.common.security.outbound.SecurityTokenSource;
import org.metaform.certo.common.security.inbound.SecurityTokenVerifier;
import org.metaform.certo.common.pc.store.ParticipantContextStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test wiring for the always-on siglet security layer: replaces the real HTTP siglet backend with an in-JVM
 * {@link MockSiglet} as the {@code @Primary} {@code SecurityTokenSource} / {@code SecurityTokenVerifier}, so
 * functional tests exercise the real security paths (verification, tenant resolution, outbound token +
 * endpoint) without running a siglet process. Import this into any {@code @SpringBootTest} that drives secured
 * protocol calls; real-server tests autowire the {@link MockSiglet} bean to point its endpoint at their loopback.
 *
 * <p>Also wires the OAuth2 side of test auth: {@link ManagementTestAuth} (the lenient management-API
 * {@code JwtDecoder}), and a MockMvc customizer that attaches a valid bearer to <em>every</em> MockMvc
 * request by default. Siglet-minted tokens carry the {@code certo-mgmt-api:admin} scope, so the one
 * default token passes both the siglet-verified protocol surface and the scope-checked management
 * surface; tests override the {@code Authorization} header per request where they need a different
 * identity. The default token is addressed to the provider tenant (a consumer calling the provider).
 */
@TestConfiguration
@Import(ManagementTestAuth.class)
public class MockSigletConfig {

    @Bean
    MockSiglet mockSiglet(ParticipantContextStore contexts) {
        return new MockSiglet(contexts, "http://localhost:8080");
    }

    @Bean
    @Primary
    SecurityTokenSource mockTokenSource(MockSiglet siglet) {
        return siglet::resolve;
    }

    @Bean
    @Primary
    SecurityTokenVerifier mockTokenVerifier(MockSiglet siglet) {
        return siglet::verify;
    }

    @Bean
    MockMvcBuilderCustomizer defaultAuthToken(MockSiglet siglet) {
        var bearer = "Bearer " + siglet.mint(TestTenants.PROVIDER_DID, TestTenants.CONSUMER_DID, TestTenants.CONSUMER_BPN);
        return builder -> builder.defaultRequest(get("/").header("Authorization", bearer));
    }
}
