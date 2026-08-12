package org.metaform.certo.common.event.nats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metaform.certo.common.cloudevent.CloudEvent;
import org.metaform.certo.common.event.CertificateExchangeStatusChanged;
import org.metaform.certo.common.event.ExchangeEventType;
import org.metaform.certo.common.event.ExchangePhase;
import org.metaform.certo.common.event.ExchangeRole;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.pc.domain.ParticipantContext;
import org.metaform.certo.testsupport.InMemoryParticipantContextStore;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CloudEvents envelope Certo puts on the wire. {@code source} and {@code sourcebpn} come from the
 * emitting tenant rather than from the process, so they are worth pinning: CX-0000 §2.1.2 makes
 * {@code sourcebpn} REQUIRED, and a consumer that de-duplicates on {@code source}+{@code id} depends
 * on both being right.
 */
class NatsEventPublisherTest {

    private InMemoryParticipantContextStore contexts;
    private NatsEventPublisher publisher;

    @BeforeEach
    void setUp() {
        contexts = new InMemoryParticipantContextStore();
        contexts.save(new ParticipantContext("pctx-1", "BPNL0000000001AB", "urn:bpn:BPNL0000000001AB", "did:web:provider"));
        // JetStream and the mapper are only exercised by the publish path, not by envelope construction.
        publisher = new NatsEventPublisher(null, null, contexts, "certo-7d56645cc7-2bzkl");
    }

    private static CertificateExchangeStatusChanged change(String participantContextId) {
        return new CertificateExchangeStatusChanged(
                ExchangeRole.PROVIDER,
                ExchangePhase.FULFILLMENT,
                ExchangeEventType.of(FulfillmentStatus.FULFILLED),
                "ex-1",
                participantContextId,
                "BPNL-CONSUMER",
                "did:web:consumer",
                "cert-1",
                2,
                "ACKNOWLEDGED",
                "FULFILLED",
                null,
                OffsetDateTime.parse("2026-08-12T10:15:30Z"));
    }

    @Test
    void envelopeCarriesTheTenantIdentityAndCatalogueType() {
        CloudEvent<CertificateExchangeStatusChanged> envelope = publisher.envelope(change("pctx-1"));

        assertThat(envelope.specVersion()).isEqualTo("1.0");
        assertThat(envelope.type()).isEqualTo("org.catena-x.ccm.CertificateExchangeFulfilled.v1");
        // source is the emitting APPLICATION, as in every other platform producer -- not the tenant.
        assertThat(envelope.source()).isEqualTo("certo-7d56645cc7-2bzkl");
        // The tenant is carried by sourcebpn (CX-0000 §2.1.2) and participantContextId in the data.
        assertThat(envelope.sourceBpn()).isEqualTo("BPNL0000000001AB");
        assertThat(envelope.dataContentType()).isEqualTo("application/json");
        assertThat(envelope.time()).isEqualTo(OffsetDateTime.parse("2026-08-12T10:15:30Z"));
        assertThat(envelope.data().exchangeId()).isEqualTo("ex-1");
    }

    @Test
    void subjectAttributeNamesTheExchange() {
        // CloudEvents `subject` identifies the thing the event is about; the NATS subject (the routing
        // key) is a separate concept and comes from the catalogue.
        assertThat(publisher.envelope(change("pctx-1")).subject()).isEqualTo("ex-1");
    }

    @Test
    void eachEnvelopeGetsItsOwnId() {
        var first = publisher.envelope(change("pctx-1"));
        var second = publisher.envelope(change("pctx-1"));

        assertThat(first.id()).isNotBlank().isNotEqualTo(second.id());
    }

    @Test
    void aDeletedTenantStillProducesAnIdentifiableEvent() {
        // The tenant can be removed between the transition committing and this listener running.
        // Dropping the event would lose a committed fact, so it goes out without a sourcebpn; the
        // application source is unaffected, and participantContextId still names the tenant.
        var envelope = publisher.envelope(change("pctx-gone"));

        assertThat(envelope.source()).isEqualTo("certo-7d56645cc7-2bzkl");
        assertThat(envelope.sourceBpn()).isNull();
        assertThat(envelope.data().participantContextId()).isEqualTo("pctx-gone");
        assertThat(envelope.data().exchangeId()).isEqualTo("ex-1");
    }
}
