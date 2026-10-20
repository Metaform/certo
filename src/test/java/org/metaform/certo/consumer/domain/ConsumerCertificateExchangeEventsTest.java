package org.metaform.certo.consumer.domain;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.event.CertificateExchangeStatusChanged;
import org.metaform.certo.common.event.ExchangePhase;
import org.metaform.certo.common.event.ExchangeRole;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer aggregate mirrors the provider-owned Fulfillment status and owns the Acceptance
 * verdict. Its Fulfillment mirror is written on every poll, so the interesting property here is that
 * an unchanged status records <em>nothing</em>.
 */
class ConsumerCertificateExchangeEventsTest {

    private static ConsumerCertificateExchange newExchange(FulfillmentStatus initial) {
        return new ConsumerCertificateExchange("ex-1", "cert-1", 3, initial, null,
                "pctx-consumer", "did:web:provider");
    }

    private static List<CertificateExchangeStatusChanged> eventsOf(ConsumerCertificateExchange exchange) {
        return exchange.domainEvents().stream().map(CertificateExchangeStatusChanged.class::cast).toList();
    }

    @Test
    void openingRecordsTheInitialStatusAsTheConsumerSide() {
        assertThat(eventsOf(newExchange(FulfillmentStatus.ACKNOWLEDGED))).singleElement().satisfies(event -> {
            assertThat(event.role()).isEqualTo(ExchangeRole.CONSUMER);
            assertThat(event.phase()).isEqualTo(ExchangePhase.FULFILLMENT);
            assertThat(event.status()).isEqualTo("ACKNOWLEDGED");
            assertThat(event.previousStatus()).isNull();
            // From the consumer's vantage point the counterparty is the provider.
            assertThat(event.counterpartyId()).isEqualTo("did:web:provider");
        });
    }

    @Test
    void mirroringAnUnchangedStatusRecordsNothing() {
        var exchange = newExchange(FulfillmentStatus.ACKNOWLEDGED);
        exchange.clearDomainEvents();

        // pollRequest() calls updateFulfillment on every poll, whether or not the provider moved.
        exchange.updateFulfillment(FulfillmentStatus.ACKNOWLEDGED, null, null, null);
        exchange.updateFulfillment(FulfillmentStatus.ACKNOWLEDGED, null, null, null);

        assertThat(eventsOf(exchange))
                .as("a poll that reports no change must not publish an event")
                .isEmpty();
    }

    @Test
    void mirroringARealChangeRecordsIt() {
        var exchange = newExchange(FulfillmentStatus.ACKNOWLEDGED);
        exchange.clearDomainEvents();

        exchange.updateFulfillment(FulfillmentStatus.FULFILLED, "cert-7", null, null);

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.previousStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(event.status()).isEqualTo("FULFILLED");
            // The certificate identity the provider has just disclosed rides along.
            assertThat(event.certificateId()).isEqualTo("cert-7");
        });
    }

    @Test
    void acceptanceVerdictIsRecorded() {
        var exchange = newExchange(FulfillmentStatus.FULFILLED);
        exchange.clearDomainEvents();

        exchange.transitionAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("bad signature")));

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.phase()).isEqualTo(ExchangePhase.ACCEPTANCE);
            assertThat(event.status()).isEqualTo("REJECTED");
            assertThat(event.errors()).containsExactly(new StatusError("bad signature"));
            assertThat(event.eventType().subject()).isEqualTo("events.certificate.exchange.rejected");
        });
    }

    @Test
    void clearingPreventsRepublicationOnASecondSave() {
        var exchange = newExchange(FulfillmentStatus.ACKNOWLEDGED);
        assertThat(exchange.domainEvents()).isNotEmpty();

        exchange.clearDomainEvents();

        assertThat(exchange.domainEvents()).isEmpty();
    }
}
