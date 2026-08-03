package org.metaform.certo.management;

import com.nimbusds.jose.JWSAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    private static final Logger LOG = LoggerFactory.getLogger(ManagementApiSecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain managementApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/management/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(loggingAuthenticationEntryPoint())
                        .accessDeniedHandler(loggingAccessDeniedHandler()));
        return http.build();
    }

    /**
     * When {@code jwk-set-uri} is set, replace Boot's decoder with the EdDSA-capable {@link
     * JwkSetJwtDecoder} — Boot's property path crashes on {@code jws-algorithms: EdDSA} ({@code
     * SignatureAlgorithm} has no such entry) and its issuer-discovery decoder infers RS256 only, so
     * neither accepts jwtlet/siglet (Ed25519) tokens. Claim validation is unchanged: {@code exp}/{@code
     * nbf} always, {@code iss} when {@code issuer-uri} is set (as a plain expected value, no discovery),
     * {@code aud} when {@code audiences} is set.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "jwk-set-uri")
    JwtDecoder jwkSetJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jws-algorithms:EdDSA,RS256,ES256}") String jwsAlgorithms,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String audiences) {
        var algorithms = splitCsv(jwsAlgorithms).stream().map(JWSAlgorithm::parse).collect(Collectors.toSet());
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(issuerUri.isBlank()
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(issuerUri));
        var expectedAudiences = splitCsv(audiences);
        if (!expectedAudiences.isEmpty()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.stream().anyMatch(expectedAudiences::contains)));
        }
        return new JwkSetJwtDecoder(jwkSetUri, algorithms, new DelegatingOAuth2TokenValidator<>(validators));
    }

    private static List<String> splitCsv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The default bearer 401 (WWW-Authenticate with error details), plus a log line saying why. */
    private static AuthenticationEntryPoint loggingAuthenticationEntryPoint() {
        var delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            LOG.warn("401 {} {}: {}", request.getMethod(), request.getRequestURI(),
                    exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "no bearer token" : exception.getMessage());
            delegate.commence(request, response, exception);
        };
    }

    /** The default bearer 403 (insufficient_scope), plus a log line naming the caller and its scopes. */
    private static AccessDeniedHandler loggingAccessDeniedHandler() {
        var delegate = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            LOG.warn("403 {} {}: sub={}, authorities={}", request.getMethod(), request.getRequestURI(),
                    authentication == null ? "?" : authentication.getName(),
                    authentication == null ? "[]" : authentication.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority).toList());
            delegate.handle(request, response, exception);
        };
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
