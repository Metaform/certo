package org.metaform.certo.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Test support: security is always on, so this attaches a valid bearer token to <em>every</em> MockMvc request
 * by default. The wiring now lives entirely in {@link MockSigletConfig} (which also covers tests that import
 * it directly); this class remains as the established entry point for MockMvc tests. The default token is
 * addressed to the provider tenant (a consumer calling the provider) and carries the {@code
 * certo-mgmt-api:admin} scope for the management surface; consumer-facing tests override the {@code
 * Authorization} header per request with a consumer-audience token. Import into a {@code
 * @AutoConfigureMockMvc} test with {@code @Import(MockMvcTokenConfig.class)}.
 */
@TestConfiguration
@Import(MockSigletConfig.class)
public class MockMvcTokenConfig {
}
