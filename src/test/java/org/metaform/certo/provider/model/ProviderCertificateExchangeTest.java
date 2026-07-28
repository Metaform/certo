package org.metaform.certo.provider.model;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATION_REQUESTED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FAILED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;

/** The provider exchange aggregate: §2.1.3 state machine, liveness, and the liveDedupKey lifecycle. */
class ProviderCertificateExchangeTest {

    private static ProviderCertificateExchange pending() {
        return ProviderCertificateExchange.pending("exch-1", "pctx-p", "BPNL-C", "did:web:c",
                "ISO9001", List.of("BPNS-1"), OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    private static ProviderCertificateExchange held() {
        return new ProviderCertificateExchange("exch-2", "pctx-p", "cert-1", 1, "BPNL-C", "did:web:c", FULFILLED);
    }

    // --- construction --------------------------------------------------------------------------

    @Test
    void constructor_rejectsBlankRequiredIdentifiers() {
        assertThatThrownBy(() -> new ProviderCertificateExchange(" ", "pctx", "c", 1, "bpn", "did", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("exchangeId");
        assertThatThrownBy(() -> new ProviderCertificateExchange("e", " ", "c", 1, "bpn", "did", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("participantContextId");
        assertThatThrownBy(() -> new ProviderCertificateExchange("e", "pctx", "c", 1, " ", "did", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("counterpartyBpn");
        assertThatThrownBy(() -> new ProviderCertificateExchange("e", "pctx", "c", 1, "bpn", " ", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("counterpartyDid");
    }

    @Test
    void pending_opensInCertificationRequested_withRequestDetail() {
        var exchange = pending();
        assertThat(exchange.fulfillmentStatus()).isEqualTo(CERTIFICATION_REQUESTED);
        assertThat(exchange.certificateId()).isNull();
        assertThat(exchange.requestedType()).isEqualTo("ISO9001");
        assertThat(exchange.requestedLocations()).containsExactly("BPNS-1");
    }

    // --- fulfillment transitions ---------------------------------------------------------------

    @Test
    void transitionFulfillment_rejectsAnIllegalMove() {
        // CERTIFICATION_REQUESTED cannot go straight to ACKNOWLEDGED.
        assertThatThrownBy(() -> pending().transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void fulfill_bindsCertificateAndTransitionsToFulfilled() {
        var exchange = pending();
        exchange.fulfill("cert-9", 3);
        assertThat(exchange.fulfillmentStatus()).isEqualTo(FULFILLED);
        assertThat(exchange.certificateId()).isEqualTo("cert-9");
        assertThat(exchange.revision()).isEqualTo(3);
    }

    @Test
    void fulfill_rejectsABlankCertificateId() {
        assertThatThrownBy(() -> pending().fulfill(" ", 1))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("certificateId");
    }

    // --- acceptance transitions (§2.1.3: feedback may follow any fulfillment outcome) -----------

    @Test
    void recordAcceptance_fromFulfilled_succeeds() {
        var exchange = held();
        exchange.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.acceptanceStatus()).isEqualTo(AcceptanceStatus.ACCEPTED);
    }

    @Test
    void recordAcceptance_isRejectedBeforeAFulfillmentOutcome() {
        assertThatThrownBy(() -> pending().recordAcceptance(AcceptanceStatus.ACCEPTED, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void recordAcceptance_isAllowedAfterADeclinedOrFailedOutcome() {
        var declined = pending();
        declined.transitionFulfillment(DECLINED, List.of(new StatusError("declined")));
        declined.recordAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("no")));
        assertThat(declined.acceptanceStatus()).isEqualTo(AcceptanceStatus.REJECTED);

        var failed = pending();
        failed.transitionFulfillment(FAILED, List.of(new StatusError("failed")));
        failed.recordAcceptance(AcceptanceStatus.ERRORED, List.of(new StatusError("bad")));
        assertThat(failed.acceptanceStatus()).isEqualTo(AcceptanceStatus.ERRORED);
    }

    @Test
    void recordAcceptance_allowsRetrievedThenTerminal_butNotOutOfTerminal() {
        var exchange = held();
        exchange.recordAcceptance(AcceptanceStatus.RETRIEVED, null);
        exchange.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.acceptanceStatus()).isEqualTo(AcceptanceStatus.ACCEPTED);

        assertThatThrownBy(() -> exchange.recordAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("x"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    // --- liveness ------------------------------------------------------------------------------

    @Test
    void isLive_trueUntilAnyPhaseGoesTerminal() {
        assertThat(pending().isLive()).isTrue();                 // in-progress fulfillment
        assertThat(held().isLive()).isTrue();                    // FULFILLED, not yet accepted

        var retrieved = held();
        retrieved.recordAcceptance(AcceptanceStatus.RETRIEVED, null);
        assertThat(retrieved.isLive()).isTrue();                 // RETRIEVED is non-terminal

        var declined = pending();
        declined.transitionFulfillment(DECLINED, List.of(new StatusError("d")));
        assertThat(declined.isLive()).isFalse();                 // terminal fulfillment

        var accepted = held();
        accepted.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(accepted.isLive()).isFalse();                 // terminal acceptance
    }

    // --- liveDedupKey lifecycle ----------------------------------------------------------------

    @Test
    void liveDedupKey_isRetainedWhileLiveAndNulledOnTerminalFulfillment() {
        var exchange = pending();
        exchange.assignLiveDedupKey("req:ISO9001");
        assertThat(exchange.liveDedupKey()).isEqualTo("req:ISO9001");

        exchange.transitionFulfillment(DECLINED, List.of(new StatusError("d")));
        assertThat(exchange.liveDedupKey()).isNull();
    }

    @Test
    void liveDedupKey_isNulledOnTerminalAcceptance_butKeptThroughRetrieved() {
        var exchange = held();
        exchange.assignLiveDedupKey("pub:key-1");

        exchange.recordAcceptance(AcceptanceStatus.RETRIEVED, null);
        assertThat(exchange.liveDedupKey()).isEqualTo("pub:key-1");   // still live

        exchange.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.liveDedupKey()).isNull();                 // terminal → released
    }

    // --- defensive copies ----------------------------------------------------------------------

    @Test
    void errorLists_areCopiedIn_soCallerMutationDoesNotLeak() {
        var exchange = pending();
        var errors = new ArrayList<>(List.of(new StatusError("first")));
        exchange.transitionFulfillment(FAILED, errors);
        errors.add(new StatusError("added-after"));                   // mutate the caller's list
        assertThat(exchange.fulfillmentErrors()).extracting(StatusError::message).containsExactly("first");
    }
}
