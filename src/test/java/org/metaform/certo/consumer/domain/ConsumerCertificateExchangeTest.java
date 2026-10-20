package org.metaform.certo.consumer.domain;


import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The consumer exchange aggregate: fulfillment mirroring, acceptance transitions, and the report-pending flag. */
class ConsumerCertificateExchangeTest {

    private static ConsumerCertificateExchange fulfilled() {
        return new ConsumerCertificateExchange("exch-1", "cert-1", 1,
                FulfillmentStatus.FULFILLED, null, "pctx-c", "did:web:p");
    }

    @Test
    void constructor_rejectsBlankRequiredIdentifiers() {
        assertThatThrownBy(() -> new ConsumerCertificateExchange(" ", "c", 1,
                FulfillmentStatus.FULFILLED, null, "pctx", "did"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("exchangeId");
        assertThatThrownBy(() -> new ConsumerCertificateExchange("e", "c", 1,
                FulfillmentStatus.FULFILLED, null, " ", "did"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("participantContextId");
        assertThatThrownBy(() -> new ConsumerCertificateExchange("e", "c", 1,
                FulfillmentStatus.FULFILLED, null, "pctx", " "))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("providerId");
    }

    @Test
    void updateFulfillment_adoptsACertificateIdWhenProvided_elseKeepsExisting() {
        var exchange = new ConsumerCertificateExchange("e", null, null,
                FulfillmentStatus.CERTIFICATE_REQUESTED, null, "pctx", "did");

        exchange.updateFulfillment(FulfillmentStatus.FULFILLED, "cert-new", null, null);
        assertThat(exchange.certificateId()).isEqualTo("cert-new");

        exchange.updateFulfillment(FulfillmentStatus.FULFILLED, null, null, null);   // null id must not wipe it
        assertThat(exchange.certificateId()).isEqualTo("cert-new");
    }

    @Test
    void transitionAcceptance_rejectsAMoveOutOfATerminalVerdict() {
        var exchange = fulfilled();
        exchange.transitionAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThatThrownBy(() -> exchange.transitionAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("x"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void acceptanceReportPending_tracksTheReportedFlag() {
        var exchange = fulfilled();
        assertThat(exchange.acceptanceReportPending()).isFalse();          // no acceptance yet

        exchange.transitionAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.acceptanceReportPending()).isTrue();           // recorded, not reported

        exchange.markAcceptanceReported();
        assertThat(exchange.acceptanceReportPending()).isFalse();          // confirmed delivered
    }

    @Test
    void markingReported_clearsThePendingFlag() {
        var exchange = fulfilled();
        exchange.transitionAcceptance(AcceptanceStatus.ACCEPTED, null);
        assertThat(exchange.acceptanceReportPending()).isTrue();

        exchange.markAcceptanceReported();
        assertThat(exchange.acceptanceReportPending()).isFalse();
    }
}
