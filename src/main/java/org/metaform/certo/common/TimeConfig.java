package org.metaform.certo.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Provides a {@link Clock} so date-dependent logic (validity, generation) is testable. */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
