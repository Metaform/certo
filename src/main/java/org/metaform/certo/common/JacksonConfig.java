package org.metaform.certo.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;

/**
 * Tunes the JSON mapper. Jackson 3 defaults {@code FAIL_ON_NULL_FOR_PRIMITIVES} to {@code true}, which rejects a
 * body that simply omits a primitive field. Request DTOs (e.g. {@code PublishRequest}) use primitives whose
 * absence is meaningful — a missing {@code boolean}/{@code int} means its type default — so this restores the
 * conventional "absent primitive maps to its default" behavior. Required fields are enforced with bean
 * validation, not by primitive-null failure.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer primitiveDefaultsCustomizer() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
