package org.metaform.certo.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Validations#requireNonBlank}. */
class ValidationsTest {

    @Test
    void returnsThePresentValue() {
        assertThat(Validations.requireNonBlank("value", "field")).isEqualTo("value");
    }

    @Test
    void throwsNamingTheFieldWhenNullOrBlank() {
        assertThatThrownBy(() -> Validations.requireNonBlank(null, "exchangeId"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("exchangeId");
        assertThatThrownBy(() -> Validations.requireNonBlank("   ", "certificateId"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("certificateId");
    }
}
