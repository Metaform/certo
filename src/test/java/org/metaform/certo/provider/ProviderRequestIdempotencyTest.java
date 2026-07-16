package org.metaform.certo.provider;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.VerifiedRequestContext;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.metaform.certo.common.model.FulfillmentStatus.CERTIFICATION_REQUESTED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;

/**
 * The consumer-initiated open is idempotent (CX-0135 §2.1.1): a repeated request from the same counterparty
 * for the same {@code certificateType} + locations reuses the still-live exchange rather than opening a
 * duplicate — so a retried open is safe. Only a request whose sole match is a terminal exchange opens a new
 * one. Each test uses a unique {@code certificateType} so the shared context's H2 database keeps them
 * isolated (and none matches a seeded certificate, so every open enters {@code CERTIFICATION_REQUESTED}).
 */
@SpringBootTest
class ProviderRequestIdempotencyTest {

    @Autowired
    ProviderExchangeService exchanges;

    @Autowired
    ProviderCertificateExchangeStore exchangeStore;

    private static VerifiedRequestContext consumer() {
        return new VerifiedRequestContext("did:web:consumer", "pctx-seed-provider",
                Map.of("bpn", "BPNL0000000002CD"));
    }

    @Test
    void repeatedOpen_reusesTheLiveExchange() {
        var request = new CertificateRequest("ISO-DEDUP-A", List.of("BPNS000000000003"));
        var first = exchanges.requestCertificate(request, consumer());
        var second = exchanges.requestCertificate(request, consumer());

        assertThat(first.status()).isEqualTo(CERTIFICATION_REQUESTED);
        assertThat(second.exchangeId()).isEqualTo(first.exchangeId());
    }

    @Test
    void locationOrderDoesNotOpenADuplicate() {
        var forward = new CertificateRequest("ISO-DEDUP-B", List.of("BPNS000000000003", "BPNA000000000001"));
        var reversed = new CertificateRequest("ISO-DEDUP-B", List.of("BPNA000000000001", "BPNS000000000003"));
        var first = exchanges.requestCertificate(forward, consumer());
        var second = exchanges.requestCertificate(reversed, consumer());

        assertThat(second.exchangeId()).isEqualTo(first.exchangeId());
    }

    @Test
    void differentCounterparty_opensItsOwnExchange() {
        var request = new CertificateRequest("ISO-DEDUP-D", List.of());
        var mine = exchanges.requestCertificate(request, consumer());
        var theirs = exchanges.requestCertificate(request,
                new VerifiedRequestContext("did:web:other", "pctx-seed-provider", Map.of("bpn", "BPNL0000000009ZZ")));

        assertThat(theirs.exchangeId()).isNotEqualTo(mine.exchangeId());
    }

    @Test
    void afterTerminalOutcome_opensANewExchange() {
        var request = new CertificateRequest("ISO-DEDUP-C", List.of());
        var first = exchanges.requestCertificate(request, consumer());

        // Drive the exchange to a terminal Fulfillment state directly, then re-request.
        var exchange = exchangeStore.find(first.exchangeId()).orElseThrow();
        exchange.transitionFulfillment(DECLINED, List.of(new StatusError("declined")));
        exchangeStore.save(exchange);

        var second = exchanges.requestCertificate(request, consumer());
        assertThat(second.exchangeId()).isNotEqualTo(first.exchangeId());
    }
}
