package org.metaform.certo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test support: security is always on, so this attaches a valid bearer token to <em>every</em> MockMvc request
 * by default. The token is minted by the in-JVM {@link MockSiglet} (pulled in via {@link MockSigletConfig});
 * management endpoints ignore it, protocol endpoints require it. The default token is addressed to the provider
 * tenant (a consumer calling the provider); consumer-facing tests override the {@code Authorization} header per
 * request with a consumer-audience token. Import into a {@code @AutoConfigureMockMvc} test with
 * {@code @Import(MockMvcTokenConfig.class)}.
 */
@TestConfiguration
@Import(MockSigletConfig.class)
public class MockMvcTokenConfig {

    @Bean
    MockMvcBuilderCustomizer defaultAuthToken(MockSiglet siglet) {
        var bearer = "Bearer " + siglet.mint(TestTenants.PROVIDER_DID, TestTenants.CONSUMER_DID, TestTenants.CONSUMER_BPN);
        return builder -> builder.defaultRequest(get("/").header("Authorization", bearer));
    }
}
