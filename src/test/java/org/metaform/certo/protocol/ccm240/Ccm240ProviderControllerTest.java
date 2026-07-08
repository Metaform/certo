package org.metaform.certo.protocol.ccm240;

import org.junit.jupiter.api.Test;
import org.metaform.certo.protocol.ExchangeBindingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the legacy v2.4.0 provider-facing adapter ({@code /companycertificate/request} + {@code /status})
 * and asserts the translated calls reach the v3 core and record acceptance on the exchange.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Ccm240ProviderControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ExchangeBindingStore correlations;

    private static final String CONSUMER_BPN = "BPNL0000000002CD";

    @Test
    void legacyRequest_heldCertificate_completed_thenStatusRecordsAcceptance() throws Exception {
        var request = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "leg-req-1", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certifiedBpn": "%s", "certificateType": "ISO9001",
                               "locationBpns": ["BPNS00000003AYRE"] } }
                """.formatted(CONSUMER_BPN, CONSUMER_BPN);

        mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.documentId").value("cert-iso9001-0001"));

        var status = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "leg-status-1", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T09:00:00Z", "version": "3.1.0" },
                  "content": { "documentId": "cert-iso9001-0001", "certificateStatus": "ACCEPTED",
                               "locationBpns": ["BPNS00000003AYRE"] } }
                """.formatted(CONSUMER_BPN);

        mvc.perform(post("/companycertificate/status").contentType(MediaType.APPLICATION_JSON).content(status))
                .andExpect(status().isOk());

        // The legacy documentId resolved back to the v3 exchange, and acceptance is recorded there.
        var exchangeId = correlations.exchangeFor("cert-iso9001-0001", CONSUMER_BPN).orElseThrow();
        mvc.perform(get("/certificate-exchanges/{id}", exchangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceStatus").value("ACCEPTED"));
    }

    @Test
    void legacyRequest_unofferedType_rejectedWithErrors() throws Exception {
        var request = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Request:1.0.0",
                              "messageId": "leg-req-2", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T08:00:00Z", "version": "3.1.0" },
                  "content": { "certificateType": "NOT-OFFERED" } }
                """.formatted(CONSUMER_BPN);

        mvc.perform(post("/companycertificate/request").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("REJECTED"))
                .andExpect(jsonPath("$.requestErrors[0].message").isNotEmpty());
    }

    @Test
    void legacyStatus_unknownDocumentId_notFound() throws Exception {
        var status = """
                { "header": { "context": "CompanyCertificateManagement-CCMAPI-Status:1.0.0",
                              "messageId": "leg-status-x", "senderBpn": "%s", "receiverBpn": "BPNL0000000001AB",
                              "sentDateTime": "2025-05-04T09:00:00Z", "version": "3.1.0" },
                  "content": { "documentId": "cert-nope", "certificateStatus": "ACCEPTED" } }
                """.formatted(CONSUMER_BPN);

        mvc.perform(post("/companycertificate/status").contentType(MediaType.APPLICATION_JSON).content(status))
                .andExpect(status().isNotFound());
    }
}
