package org.metaform.certo.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;
import static org.metaform.certo.common.model.FulfillmentStatus.RECEIVED;

/** The Fulfillment-phase state machine (SCE exchange state machine). */
class FulfillmentStatusTest {

    @Test
    void allowedTransitions_matchTheStateMachine() {
        assertThat(RECEIVED.allowedNext()).containsExactlyInAnyOrder(FULFILLED, DECLINED);
    }

    @Test
    void fulfillmentDeadEnds_haveNoOnwardTransition() {
        assertThat(FULFILLED.allowedNext()).isEmpty();
        assertThat(DECLINED.allowedNext()).isEmpty();
    }

    @Test
    void isTerminal_marksFulfillmentDeadEndsOnly() {
        // isTerminal() = "no further *fulfillment* transition". FULFILLED is not terminal — the exchange
        // continues into the Acceptance phase; DECLINED is the one negative dead end.
        assertThat(DECLINED.isTerminal()).isTrue();
        assertThat(FULFILLED.isTerminal()).isFalse();
        assertThat(RECEIVED.isTerminal()).isFalse();
    }

    @Test
    void aTerminalStatusIsNeverInItsOwnAllowedNext() {
        for (var status : FulfillmentStatus.values()) {
            assertThat(status.allowedNext()).doesNotContain(status);
        }
    }
}
