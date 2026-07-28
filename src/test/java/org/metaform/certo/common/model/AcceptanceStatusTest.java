package org.metaform.certo.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.metaform.certo.common.model.AcceptanceStatus.ACCEPTED;
import static org.metaform.certo.common.model.AcceptanceStatus.ERRORED;
import static org.metaform.certo.common.model.AcceptanceStatus.REJECTED;
import static org.metaform.certo.common.model.AcceptanceStatus.RETRIEVED;

/** The Acceptance-phase state machine (CX-0135 §2.1.3). */
class AcceptanceStatusTest {

    @Test
    void retrievedMayReachAnyTerminalVerdict() {
        assertThat(RETRIEVED.allowedNext()).containsExactlyInAnyOrder(ACCEPTED, REJECTED, ERRORED);
    }

    @Test
    void terminalVerdictsHaveNoOnwardTransition() {
        assertThat(ACCEPTED.allowedNext()).isEmpty();
        assertThat(REJECTED.allowedNext()).isEmpty();
        assertThat(ERRORED.allowedNext()).isEmpty();
    }

    @Test
    void isTerminal_marksTheVerdicts() {
        assertThat(ACCEPTED.isTerminal()).isTrue();
        assertThat(REJECTED.isTerminal()).isTrue();
        assertThat(ERRORED.isTerminal()).isTrue();
        assertThat(RETRIEVED.isTerminal()).isFalse();
    }

    @Test
    void requiresErrors_forNegativeVerdictsOnly() {
        assertThat(REJECTED.requiresErrors()).isTrue();
        assertThat(ERRORED.requiresErrors()).isTrue();
        assertThat(ACCEPTED.requiresErrors()).isFalse();
        assertThat(RETRIEVED.requiresErrors()).isFalse();
    }
}
