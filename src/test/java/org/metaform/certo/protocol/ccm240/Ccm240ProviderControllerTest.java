package org.metaform.certo.protocol.ccm240;

import org.junit.jupiter.api.Test;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.metaform.certo.MockMvcTokenConfig;
import org.metaform.certo.TestTenants;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the v2.4.0 provider-facing adapter ({@code /companycertificate/request} + {@code /status})
 * and asserts the translated calls reach the v3 core and record acceptance on the exchange.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcTokenConfig.class)
class Ccm240ProviderControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ExchangeBindingStore correlations;

    private static final String CONSUMER_BPN = "BPNL0000000002CD";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void v240Request_heldCertificate_completed_thenStatusRecordsAcceptance() throws Exception {
        var request = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "11111111-1111-1111-1111-111111111111", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certifiedBpn": "%s", "certificateType": "ISO9001",
                               "locationBpns": ["BPNS00000003AYRE"] } }
                """.formatted(CONSUMER_BPN, CONSUMER_BPN);

        var completed = mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andReturn();
        // The documentId is a UUID (the v2.4.0 asset id), not the internal certificateId.
        var documentId = mapper.readTree(completed.getResponse().getContentAsString()).get("documentId").asString();
        assertThat(documentId).matches(UUID_PATTERN);

        var status = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "22222222-2222-2222-2222-222222222222", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T09:00:00Z", "version": "3.1.0" },
                  "content": { "documentId": "%s", "certificateStatus": "ACCEPTED",
                               "locationBpns": ["BPNS00000003AYRE"] } }
                """.formatted(CONSUMER_BPN, documentId);

        mvc.perform(post("/companycertificate/status").contentType(MediaType.APPLICATION_JSON).content(status))
                .andExpect(status().isOk());

        // The UUID documentId resolved back to the certificate and its v3 exchange; acceptance is recorded there.
        var exchangeId = correlations.exchangeFor(TestTenants.ISO9001_CERT_ID, CONSUMER_BPN).orElseThrow();
        mvc.perform(get("/management/v1/participant-contexts/" + "pctx-seed-provider" + "/certificate-exchanges/{id}", exchangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceStatus").value("ACCEPTED"));
    }

    @Test
    void v240Request_completedReturnsOnlyDocumentId() throws Exception {
        // v2.4.0-compliant: a COMPLETED reply carries only the documentId, never the certificate inline.
        var request = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "33333333-3333-3333-3333-333333333333", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certifiedBpn": "%s", "certificateType": "ISO9001", "locationBpns": ["BPNS00000003AYRE"] } }
                """.formatted(CONSUMER_BPN, CONSUMER_BPN);

        mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.documentId", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.certificate").doesNotExist());
    }

    @Test
    void v240Request_notHeld_inProgress() throws Exception {
        var request = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "44444444-4444-4444-4444-444444444444", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certifiedBpn": "%s", "certificateType": "ISO50001" } }
                """.formatted(CONSUMER_BPN, CONSUMER_BPN);

        mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestStatus").value("IN_PROGRESS"));
    }

    @Test
    void v240Status_unknownDocumentId_notFound() throws Exception {
        // A well-formed but never-issued documentId (UUID) resolves to no certificate -> 404.
        var status = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "55555555-5555-5555-5555-555555555555", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T09:00:00Z", "version": "3.1.0" },
                  "content": { "documentId": "99999999-9999-9999-9999-999999999999", "certificateStatus": "ACCEPTED" } }
                """.formatted(CONSUMER_BPN);

        mvc.perform(post("/companycertificate/status").contentType(MediaType.APPLICATION_JSON).content(status))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedEnvelope_isBadRequest() throws Exception {
        // Bad BPNL pattern.
        post400("""
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "11111111-1111-1111-1111-111111111111", "senderBpn": "NOT-A-BPN",
                              "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certificateType": "ISO9001" } }""");
        // Non-UUID messageId.
        post400("""
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "not-a-uuid", "senderBpn": "BPNL0000000002CD",
                              "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certificateType": "ISO9001" } }""");
        // Wrong context for the endpoint.
        post400("""
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "11111111-1111-1111-1111-111111111111", "senderBpn": "BPNL0000000002CD",
                              "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certificateType": "ISO9001" } }""");
        // Missing required field (version).
        post400("""
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "11111111-1111-1111-1111-111111111111", "senderBpn": "BPNL0000000002CD",
                              "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T08:00:00Z" },
                  "content": { "certificateType": "ISO9001" } }""");
    }

    private void post400(String request) throws Exception {
        mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedContent_isBadRequest() throws Exception {
        var requestHeader = """
                "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                            "messageId": "11111111-1111-1111-1111-111111111111", "senderBpn": "BPNL0000000002CD",
                            "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" }""";
        // certifiedBpn is not a BPNL.
        post400("{ " + requestHeader + ", \"content\": { \"certifiedBpn\": \"NOPE\", \"certificateType\": \"ISO9001\" } }");
        // locationBpns entry is not a BPNS/BPNA.
        post400("{ " + requestHeader + ", \"content\": { \"certifiedBpn\": \"BPNL0000000002CD\","
                + " \"certificateType\": \"ISO9001\", \"locationBpns\": [\"BPNL0000000002CD\"] } }");

        // status documentId is not a UUID.
        var status = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "22222222-2222-2222-2222-222222222222", "senderBpn": "BPNL0000000002CD",
                              "receiverBpn": "BPNL0000000001AB", "sentDateTime": "2025-05-04T09:00:00Z", "version": "3.1.0" },
                  "content": { "documentId": "not-a-uuid", "certificateStatus": "ACCEPTED" } }""";
        mvc.perform(post("/companycertificate/status").contentType(MediaType.APPLICATION_JSON).content(status))
                .andExpect(status().isBadRequest());
    }
}
