package org.metaform.certo.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.metaform.certo.common.model.AcceptanceStatus.ACCEPTED;
import static org.metaform.certo.common.model.AcceptanceStatus.REJECTED;

/** The Acceptance-phase state machine (SCE exchange state machine). */
class AcceptanceStatusTest {

    @Test
    void terminalVerdictsHaveNoOnwardTransition() {
        assertThat(ACCEPTED.allowedNext()).isEmpty();
        assertThat(REJECTED.allowedNext()).isEmpty();
    }

    @Test
    void isTerminal_marksBothVerdicts() {
        assertThat(ACCEPTED.isTerminal()).isTrue();
        assertThat(REJECTED.isTerminal()).isTrue();
    }

    @Test
    void requiresErrors_forTheNegativeVerdictOnly() {
        assertThat(REJECTED.requiresErrors()).isTrue();
        assertThat(ACCEPTED.requiresErrors()).isFalse();
    }
}
