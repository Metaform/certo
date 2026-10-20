package org.metaform.certo.common.security.inbound;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link SecurityTokenInterceptor} on the protocol paths only, explicitly excluding the
 * management surface and actuator. Security is always on.
 */
@Configuration
public class SecurityWebConfig implements WebMvcConfigurer {

    private final SecurityTokenInterceptor interceptor;

    public SecurityWebConfig(SecurityTokenInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns(
                        // v3.0.0 (ccm300)
                        "/certificate-requests/**", "/certificates/**", "/documents/**",
                        "/certificate-notifications", "/certificate-acceptance-notifications",
                        "/certificate-acceptance-status/**")
                .excludePathPatterns("/management/**", "/actuator/**", "/error");
    }
}
