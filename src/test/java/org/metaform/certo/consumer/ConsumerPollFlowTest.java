package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the consumer-initiated <em>poll</em> alternative to the fulfillment push (CX-0135 &sect;4.4.2).
 * The consumer base URL points at a closed port, so the provider's fulfillment push cannot reach the
 * consumer — the consumer must learn the exchange is {@code FULFILLED} by polling, which then drives
 * retrieval and acceptance. Runs on its own port (18081) to avoid clashing with the push-flow context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18081",
                "certo.provider-base-url=http://localhost:18081",
                "certo.consumer-base-url=http://localhost:59999"
        })
class ConsumerPollFlowTest {

    private static final String BASE = "http://localhost:18081";

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObjectMapper mapper;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-06-04T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void consumerInitiatedPull_pollForFulfillment_retrievesAndAccepts() throws Exception {
        // Open a request for a not-yet-held certificate -> CERTIFICATION_REQUESTED (awaiting backend).
        var exchangeId = mapper.readTree(postJson("/management/v1/consumer/certificate-requests",
                "{\"certificateType\":\"ISO14001\",\"certifiedLocations\":[\"BPNS-POLL-1\"]}").body())
                .get("exchangeId").asString();
        assertThat(getAcceptanceStatus(exchangeId).statusCode()).isEqualTo(404); // not retrieved yet

        // The backend issues the certificate; the FULFILLED push to the (unreachable) consumer base URL fails silently.
        var docId = mapper.readTree(postJson("/management/v1/documents",
                "{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}").body())
                .get("documentId").asString();
        var certBody = """
                {"certificateType":"ISO14001","certificateTypeVersion":"2015","registrationNumber":"DE-CERT-0002",
                 "validFrom":"2020-01-01","validUntil":"2035-01-01","trustLevel":"high",
                 "certifiedLocations":[{"bpnl":"BPNL000000TESTLE","bpna":"BPNA000000TESTAD","bpns":"BPNS-POLL-1","locationRole":"MAIN_LOCATION"}],
                 "documentIds":["%s"]}""".formatted(docId);
        postJson("/management/v1/certificates", certBody);

        // Polling learns it's FULFILLED -> the consumer retrieves and accepts.
        var polled = mapper.readTree(postEmpty("/management/v1/consumer/certificate-requests/" + exchangeId + "/poll").body());
        assertThat(polled.get("fulfillmentStatus").asString()).isEqualTo("FULFILLED");

        assertThat(mapper.readTree(getAcceptanceStatus(exchangeId).body()).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(mapper.readTree(get("/management/v1/certificate-exchanges/" + exchangeId).body())
                .get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAcceptanceStatus(String exchangeId) throws Exception {
        return get("/certificate-acceptance-status/" + exchangeId);
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
