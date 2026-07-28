package org.metaform.certo.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.metaform.certo.common.model.FulfillmentStatus.ACKNOWLEDGED;
import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATION_REQUESTED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FAILED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;
import static org.metaform.certo.common.model.FulfillmentStatus.REQUESTED;

/** The Fulfillment-phase state machine (CX-0135 §2.1.3). */
class FulfillmentStatusTest {

    @Test
    void allowedTransitions_matchTheStateMachine() {
        assertThat(REQUESTED.allowedNext()).containsExactlyInAnyOrder(ACKNOWLEDGED, DECLINED);
        assertThat(ACKNOWLEDGED.allowedNext()).containsExactlyInAnyOrder(CERTIFICATION_REQUESTED, FULFILLED, FAILED, DECLINED);
        assertThat(CERTIFICATION_REQUESTED.allowedNext()).containsExactlyInAnyOrder(FULFILLED, FAILED, DECLINED);
    }

    @Test
    void fulfillmentDeadEnds_haveNoOnwardTransition() {
        assertThat(FULFILLED.allowedNext()).isEmpty();
        assertThat(DECLINED.allowedNext()).isEmpty();
        assertThat(FAILED.allowedNext()).isEmpty();
    }

    @Test
    void isTerminal_marksFulfillmentDeadEndsOnly() {
        // isTerminal() = "no further *fulfillment* transition". FULFILLED is not terminal — the exchange
        // continues into the Acceptance phase; DECLINED/FAILED are the true dead ends.
        assertThat(DECLINED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(FULFILLED.isTerminal()).isFalse();
        assertThat(REQUESTED.isTerminal()).isFalse();
        assertThat(ACKNOWLEDGED.isTerminal()).isFalse();
        assertThat(CERTIFICATION_REQUESTED.isTerminal()).isFalse();
    }

    @Test
    void aTerminalStatusIsNeverInItsOwnAllowedNext() {
        for (var status : FulfillmentStatus.values()) {
            assertThat(status.allowedNext()).doesNotContain(status);
        }
    }
}
