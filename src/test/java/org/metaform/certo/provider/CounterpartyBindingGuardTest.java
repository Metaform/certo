package org.metaform.certo.provider;

import org.metaform.certo.testsupport.MockSiglet;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.metaform.certo.testsupport.TestTenants;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 (negative): inbound acceptance is bound to the exchange's <b>verified</b> counterparty. An exchange opened
 * by consumer A (token subject {@code did:web:consumer-a}) can only receive acceptance feedback from A — a
 * feedback whose token subject is a different DID resolves to nothing (404), even when its CloudEvent envelope
 * self-declares A's BPN. So one counterparty cannot forge another's feedback within the same tenant. The
 * guard keys on the token subject, not the wire envelope ({@code ProviderExchangeService.prepareAcceptance}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=18091")
@Import(MockSigletConfig.class)
class CounterpartyBindingGuardTest {

    private static final String BASE = "http://localhost:18091";
    private static final String DID_A = "did:web:consumer-a";
    private static final String DID_B = "did:web:consumer-b";
    private static final String BPN_A = "BPNL000000000A0A";
    private static final String BPN_B = "BPNL000000000B0B";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockSiglet siglet;

    @Test
    void acceptanceFromADifferentVerifiedDidIsRejected() throws Exception {
        // Consumer A opens an exchange at the provider (its verified DID becomes the exchange counterparty).
        var open = post("/certificate-requests", "{\"certificateType\":\"T2-GUARD-TYPE\"}", tokenFor(DID_A, BPN_A));
        assertThat(open.statusCode()).isEqualTo(202);
        var exchangeId = mapper.readTree(open.body()).get("exchangeId").asString();

        // B posts acceptance for A's exchange — envelope claims A's BPN, but the verified token subject is B.
        var fromB = post("/certificate-acceptance-notifications", acceptance(exchangeId, BPN_A), tokenFor(DID_B, BPN_B));
        assertThat(fromB.statusCode()).as("a different counterparty's feedback must not resolve the exchange").isEqualTo(404);

        // A posts acceptance for its own exchange — resolves past the guard; fails only on the state check
        // (the exchange has no fulfillment outcome yet), i.e. a 409, NOT a 404.
        var fromA = post("/certificate-acceptance-notifications", acceptance(exchangeId, BPN_A), tokenFor(DID_A, BPN_A));
        assertThat(fromA.statusCode()).as("the bound counterparty resolves the exchange (409 on state, not 404)").isEqualTo(409);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** An inbound bearer addressed to the provider tenant (aud) from the given consumer identity (sub/bpn). */
    private String tokenFor(String consumerDid, String consumerBpn) {
        return siglet.mint(TestTenants.PROVIDER_DID, consumerDid, consumerBpn);
    }

    private static String acceptance(String exchangeId, String sourceBpn) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:%s",
                  "sourcebpn": "%s",
                  "id": "aaaaaaaa-0000-0000-0000-000000000001",
                  "data": { "exchangeId": "%s", "certificateId": "cert-x", "status": "ACCEPTED" }
                }
                """.formatted(sourceBpn, sourceBpn, exchangeId);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
