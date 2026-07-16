package org.metaform.certo;

import org.metaform.certo.common.security.SecurityTokenSource;
import org.metaform.certo.common.security.SecurityTokenVerifier;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test wiring for the always-on siglet security layer: replaces the real HTTP siglet backend with an in-JVM
 * {@link MockSiglet} as the {@code @Primary} {@code SecurityTokenSource} / {@code SecurityTokenVerifier}, so
 * functional tests exercise the real security paths (verification, tenant resolution, outbound token +
 * endpoint) without running a siglet process. Import this into any {@code @SpringBootTest} that drives secured
 * protocol calls; real-server tests autowire the {@link MockSiglet} bean to point its endpoint at their loopback.
 */
@TestConfiguration
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
}
