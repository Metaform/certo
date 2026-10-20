package org.metaform.certo.provider.domain;


import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.metaform.certo.common.model.FulfillmentStatus.PENDING;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;
import static org.metaform.certo.common.model.FulfillmentStatus.FULFILLED;

/** The provider exchange aggregate: the SCE state machine, liveness, and the messageId idempotency key. */
class ProviderCertificateExchangeTest {

    private static ProviderCertificateExchange pending() {
        return ProviderCertificateExchange.pending("exch-1", "pctx-p", "did:web:c",
                "iso9001", List.of(new LocationFilter(null, "SITE-1", null)), OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    private static ProviderCertificateExchange held() {
        return new ProviderCertificateExchange("exch-2", "pctx-p", "cert-1", 1, "did:web:c", FULFILLED);
    }

    // --- construction --------------------------------------------------------------------------

    @Test
    void constructor_rejectsBlankRequiredIdentifiers() {
        assertThatThrownBy(() -> new ProviderCertificateExchange(" ", "pctx", "c", 1, "did", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("exchangeId");
        assertThatThrownBy(() -> new ProviderCertificateExchange("e", " ", "c", 1, "did", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("participantContextId");
        assertThatThrownBy(() -> new ProviderCertificateExchange("e", "pctx", "c", 1, " ", FULFILLED))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("counterpartyId");
    }

    @Test
    void pending_opensInCertificationRequested_withRequestDetail() {
        var exchange = pending();
        assertThat(exchange.fulfillmentStatus()).isEqualTo(PENDING);
        assertThat(exchange.certificateId()).isNull();
        assertThat(exchange.requestedType()).isEqualTo("iso9001");
        assertThat(exchange.requestedLocations()).containsExactly(new LocationFilter(null, "SITE-1", null));
    }

    // --- fulfillment transitions ---------------------------------------------------------------

    @Test
    void transitionFulfillment_rejectsAnIllegalMove() {
        // PENDING cannot go straight to PENDING.
        assertThatThrownBy(() -> pending().transitionFulfillment(FulfillmentStatus.PENDING, null))
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

    // --- acceptance transitions (SCE: feedback only from FULFILLED) ------------------------------

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
    void recordAcceptance_isRejectedAfterADeclinedOutcome() {
        var declined = pending();
        declined.transitionFulfillment(DECLINED, List.of(new StatusError("declined")));
        assertThatThrownBy(() -> declined.recordAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("no"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void recordAcceptance_terminalIsImmutable_butARepeatOfTheSameStateIsIgnored() {
        var exchange = held();
        exchange.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.acceptanceStatus()).isEqualTo(AcceptanceStatus.ACCEPTED);

        // A re-report of the current state is a duplicate (SCE): ignored, no transition, no 409.
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

        var declined = pending();
        declined.transitionFulfillment(DECLINED, List.of(new StatusError("d")));
        assertThat(declined.isLive()).isFalse();                 // terminal fulfillment

        var accepted = held();
        accepted.recordAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(accepted.isLive()).isFalse();                 // terminal acceptance
    }

    // --- messageId idempotency key ---------------------------------------------------------------

    @Test
    void messageId_isRetainedThroughTerminalStates() {
        // The creation-idempotency key is never released: a repeat of the same message resolves to this
        // exchange even after a terminal outcome (a new request carries a new messageId).
        var exchange = pending();
        exchange.assignMessageId("4c0e8e2e-0000-4000-8000-000000000001");

        exchange.transitionFulfillment(DECLINED, List.of(new StatusError("d")));
        assertThat(exchange.messageId()).isEqualTo("4c0e8e2e-0000-4000-8000-000000000001");
    }

    // --- defensive copies ----------------------------------------------------------------------

    @Test
    void errorLists_areCopiedIn_soCallerMutationDoesNotLeak() {
        var exchange = pending();
        var errors = new ArrayList<>(List.of(new StatusError("first")));
        exchange.transitionFulfillment(DECLINED, errors);
        errors.add(new StatusError("added-after"));                   // mutate the caller's list
        assertThat(exchange.fulfillmentErrors()).extracting(StatusError::message).containsExactly("first");
    }
}
