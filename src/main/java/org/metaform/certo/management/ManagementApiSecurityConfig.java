package org.metaform.certo.management;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 security for the <b>management</b> surface only. {@code /management/**} is a JWT resource
 * server: callers present a bearer access token from the issuer configured under {@code
 * spring.security.oauth2.resourceserver.jwt} (standard claim validation — signature via the issuer's
 * JWKS, {@code iss}, {@code exp}/{@code nbf}). Authorization is scope-based per endpoint via {@link
 * ManagementScopeAuthorization} ({@code @PreAuthorize} on the controllers), with {@code
 * certo-mgmt-api:admin} superseding every fine-grained scope.
 *
 * <p>Everything else — the CCM protocol surface, {@code /info}, {@code /error} — is deliberately left
 * to the existing mechanisms: protocol endpoints are verified by the always-on siglet token
 * interceptor (see {@code SecurityWebConfig}), which is a different trust domain than the management
 * IdP, so the permit-all chain here is not an unprotected surface.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ManagementApiSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain managementApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/management/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain protocolAndPublicChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
