package org.metaform.certo.provider.domain;

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
 * The provider aggregate records a status change for every mutation, which Spring Data drains on
 * {@code save()}. These tests pin that down at the source — if a transition stops recording, no
 * amount of correct plumbing downstream will publish it.
 */
class ProviderCertificateExchangeEventsTest {

    private static ProviderCertificateExchange newExchange(FulfillmentStatus initial) {
        return new ProviderCertificateExchange("ex-1", "pctx-1", "cert-1", 3, "BPNL-CONSUMER", "did:web:consumer", initial);
    }

    private static List<CertificateExchangeStatusChanged> eventsOf(ProviderCertificateExchange exchange) {
        return exchange.domainEvents().stream().map(CertificateExchangeStatusChanged.class::cast).toList();
    }

    @Test
    void openingRecordsTheInitialStatusWithNoPredecessor() {
        var events = eventsOf(newExchange(FulfillmentStatus.REQUESTED));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.role()).isEqualTo(ExchangeRole.PROVIDER);
            assertThat(event.phase()).isEqualTo(ExchangePhase.FULFILLMENT);
            assertThat(event.status()).isEqualTo("REQUESTED");
            // The distinguishing mark of an opened exchange versus a transition into the same state.
            assertThat(event.previousStatus()).isNull();
            assertThat(event.exchangeId()).isEqualTo("ex-1");
            assertThat(event.participantContextId()).isEqualTo("pctx-1");
            assertThat(event.counterpartyBpn()).isEqualTo("BPNL-CONSUMER");
            assertThat(event.counterpartyDid()).isEqualTo("did:web:consumer");
            assertThat(event.occurredAt()).isNotNull();
        });
    }

    @Test
    void transitionRecordsBothEnds() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        exchange.clearDomainEvents();

        exchange.transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null);

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.previousStatus()).isEqualTo("REQUESTED");
            assertThat(event.status()).isEqualTo("ACKNOWLEDGED");
            assertThat(event.eventType().subject()).isEqualTo("events.certificate.exchange.acknowledged");
        });
    }

    @Test
    void terminalTransitionCarriesItsErrors() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        exchange.clearDomainEvents();

        exchange.transitionFulfillment(FulfillmentStatus.DECLINED, List.of(new StatusError("not our site")));

        assertThat(eventsOf(exchange)).singleElement().satisfies(event ->
                assertThat(event.errors()).containsExactly(new StatusError("not our site")));
    }

    @Test
    void fulfillRecordsOneEventCarryingTheNewlyBoundCertificate() {
        var exchange = ProviderCertificateExchange.pending(
                "ex-2", "pctx-1", "BPNL-CONSUMER", "did:web:consumer", "ISO9001", List.of("BPNS-1"), null);
        exchange.clearDomainEvents();

        exchange.fulfill("cert-9", 2);

        // fulfill() delegates to transitionFulfillment, so exactly one event — not one per mutator.
        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo("FULFILLED");
            assertThat(event.previousStatus()).isEqualTo("CERTIFICATION_REQUESTED");
            assertThat(event.certificateId()).isEqualTo("cert-9");
            assertThat(event.revision()).isEqualTo(2);
        });
    }

    @Test
    void pendingExchangeReportsNoCertificateIdentityYet() {
        var exchange = ProviderCertificateExchange.pending(
                "ex-3", "pctx-1", "BPNL-CONSUMER", "did:web:consumer", "ISO9001", List.of("BPNS-1"), null);

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo("CERTIFICATION_REQUESTED");
            assertThat(event.certificateId()).isNull();
            // Revision is meaningless without a certificate; it must not leak a default 0.
            assertThat(event.revision()).isNull();
        });
    }

    @Test
    void acceptanceIsRecordedOnTheAcceptancePhase() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        exchange.transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null);
        exchange.transitionFulfillment(FulfillmentStatus.FULFILLED, null);
        exchange.clearDomainEvents();

        exchange.recordAcceptance(AcceptanceStatus.ACCEPTED, null);

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.phase()).isEqualTo(ExchangePhase.ACCEPTANCE);
            assertThat(event.status()).isEqualTo("ACCEPTED");
            // First verdict recorded: no acceptance status preceded it.
            assertThat(event.previousStatus()).isNull();
            assertThat(event.eventType().subject()).isEqualTo("events.certificate.exchange.accepted");
        });
    }

    @Test
    void acceptanceTransitionCarriesThePreviousVerdict() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        exchange.transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null);
        exchange.transitionFulfillment(FulfillmentStatus.FULFILLED, null);
        exchange.recordAcceptance(AcceptanceStatus.RETRIEVED, null);
        exchange.clearDomainEvents();

        exchange.recordAcceptance(AcceptanceStatus.REJECTED, List.of(new StatusError("wrong scope")));

        assertThat(eventsOf(exchange)).singleElement().satisfies(event -> {
            assertThat(event.previousStatus()).isEqualTo("RETRIEVED");
            assertThat(event.status()).isEqualTo("REJECTED");
        });
    }

    @Test
    void everyTransitionAccumulatesUntilDrained() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        exchange.transitionFulfillment(FulfillmentStatus.ACKNOWLEDGED, null);
        exchange.transitionFulfillment(FulfillmentStatus.FULFILLED, null);

        // A single save() must publish the whole trail, not just the last hop.
        assertThat(eventsOf(exchange)).extracting(CertificateExchangeStatusChanged::status)
                .containsExactly("REQUESTED", "ACKNOWLEDGED", "FULFILLED");
    }

    @Test
    void clearingPreventsRepublicationOnASecondSave() {
        var exchange = newExchange(FulfillmentStatus.REQUESTED);
        assertThat(exchange.domainEvents()).isNotEmpty();

        exchange.clearDomainEvents();

        assertThat(exchange.domainEvents()).isEmpty();
    }
}
