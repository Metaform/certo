package org.metaform.certo.provider;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.common.security.inbound.VerifiedRequestContext;
import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.sce.Sce;
import org.metaform.certo.provider.dto.CertificateRequest;
import org.metaform.certo.provider.store.ProviderCertificateExchangeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.metaform.certo.common.model.FulfillmentStatus.RECEIVED;
import static org.metaform.certo.common.model.FulfillmentStatus.DECLINED;

/**
 * Exchange creation is idempotent on (counterparty, {@code messageId}): a request repeating the pair is the
 * same message and returns the already-created exchange — whatever state it is in, terminal included. A
 * genuinely new request carries a new {@code messageId} and opens a new exchange, even for identical
 * content. Each test uses a unique {@code certificateType} so the shared context's H2 database keeps them
 * isolated (and none matches a seeded certificate, so every open enters {@code RECEIVED}).
 */
@SpringBootTest
class ProviderRequestIdempotencyTest {

    @Autowired
    ProviderExchangeService exchanges;

    @Autowired
    ProviderCertificateExchangeStore exchangeStore;

    private static VerifiedRequestContext consumer() {
        return new VerifiedRequestContext("did:web:consumer", "PRT000000000002", "pctx-seed-provider",
                Map.of("dataspaceidentifier", "PRT000000000002"));
    }

    @Test
    void repeatedMessageId_returnsTheSameExchange() {
        var messageId = UUID.randomUUID().toString();
        var request = new CertificateRequest(messageId, "isodedupa", List.of(new LocationFilter(null, "SITE000000000003", null)));
        var first = exchanges.requestCertificate(request, consumer(), Sce.PROTOCOL_ID);
        var second = exchanges.requestCertificate(request, consumer(), Sce.PROTOCOL_ID);

        assertThat(first.fulfillmentStatus()).isEqualTo(RECEIVED);
        assertThat(second.exchangeId()).isEqualTo(first.exchangeId());
    }

    @Test
    void aNewMessageId_opensANewExchange_evenForIdenticalContent() {
        var first = exchanges.requestCertificate(
                new CertificateRequest(UUID.randomUUID().toString(), "isodedupb", List.of(new LocationFilter(null, "SITE000000000003", null))),
                consumer(), Sce.PROTOCOL_ID);
        var second = exchanges.requestCertificate(
                new CertificateRequest(UUID.randomUUID().toString(), "isodedupb", List.of(new LocationFilter(null, "SITE000000000003", null))),
                consumer(), Sce.PROTOCOL_ID);

        assertThat(second.exchangeId()).isNotEqualTo(first.exchangeId());
    }

    @Test
    void sameMessageId_differentCounterparty_opensItsOwnExchange() {
        var messageId = UUID.randomUUID().toString();
        var request = new CertificateRequest(messageId, "isodedupd", List.of());
        var mine = exchanges.requestCertificate(request, consumer(), Sce.PROTOCOL_ID);
        var theirs = exchanges.requestCertificate(request,
                new VerifiedRequestContext("did:web:other", "PRT000000000009", "pctx-seed-provider", Map.of("dataspaceidentifier", "PRT000000000009")), Sce.PROTOCOL_ID);

        assertThat(theirs.exchangeId()).isNotEqualTo(mine.exchangeId());
    }

    @Test
    void repeatedMessageId_afterATerminalOutcome_stillReturnsThatExchange() {
        var messageId = UUID.randomUUID().toString();
        var request = new CertificateRequest(messageId, "isodedupc", List.of());
        var first = exchanges.requestCertificate(request, consumer(), Sce.PROTOCOL_ID);

        // Drive the exchange to a terminal Fulfillment state, then repeat the same message.
        var exchange = exchangeStore.findById(first.exchangeId()).orElseThrow();
        exchange.transitionFulfillment(DECLINED, List.of(new StatusError("declined")));
        exchangeStore.save(exchange);

        // The repeat is a retry of the SAME message, so the terminal outcome stays correlatable; a
        // re-attempt is a new exchange only because it carries a new messageId.
        var second = exchanges.requestCertificate(request, consumer(), Sce.PROTOCOL_ID);
        assertThat(second.exchangeId()).isEqualTo(first.exchangeId());
        assertThat(second.fulfillmentStatus()).isEqualTo(DECLINED);
    }

    @Test
    void aMissingOrMalformedMessageId_isRejected() {
        assertThatThrownBy(() -> exchanges.requestCertificate(
                new CertificateRequest(null, "isodedupe", List.of()), consumer(), Sce.PROTOCOL_ID))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> exchanges.requestCertificate(
                new CertificateRequest("not-a-uuid", "isodedupe", List.of()), consumer(), Sce.PROTOCOL_ID))
                .isInstanceOf(ApiException.class);
    }
}
