package org.metaform.certo.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CertificateProviderApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void requestOfferedType_fulfilledImmediately_andPollable() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"locationBpns\":[\"BPNS00000003AYRE\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.certificateId").value("cert-iso9001-0001"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();

        var body = mapper.readTree(result.getResponse().getContentAsString());
        var exchangeId = body.get("exchangeId").asString();

        mvc.perform(get("/certificate-requests/{id}", exchangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.status").value("FULFILLED"));
    }

    @Test
    void requestUnofferedType_declinedWithErrors() throws Exception {
        mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"UNKNOWN-CERT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void requestStatus_unknownExchange_notFound() throws Exception {
        mvc.perform(get("/certificate-requests/{id}", "exch-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void requestMissingType_badRequest() throws Exception {
        mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retrieveCertificate_returnsMultipartWithMetadataAndPdf() throws Exception {
        var result = mvc.perform(get("/certificates/{id}", "cert-iso9001-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("multipart/related")))
                .andReturn();

        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("Content-Type: application/json");
        assertThat(body).contains("\"certificateId\":\"cert-iso9001-0001\"");
        assertThat(body).contains("\"version\":2"); // latest by default
        assertThat(body).contains("Content-Type: application/pdf");
        assertThat(body).contains("%PDF-1.4");
    }

    @Test
    void retrieveCertificate_specificVersion() throws Exception {
        var result = mvc.perform(get("/certificates/{id}", "cert-iso9001-0001").param("version", "1"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"version\":1");
    }

    @Test
    void retrieveCertificate_unknown_notFound() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void query_byType_returnsLatestVersion() throws Exception {
        mvc.perform(post("/certificates/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].certificateId").value("cert-iso9001-0001"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].datasetId").value("dataset-ccm-cert-abc123"));
    }

    @Test
    void acceptanceNotification_recordsStatus_thenUnknownIs404_andRejectedNeedsErrors() throws Exception {
        // Open an exchange via a request.
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO14001\"}"))
                .andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var exchangeId = body.get("exchangeId").asString();
        var certificateId = body.get("certificateId").asString();

        // ACCEPTED acceptance event is recorded.
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        // Unknown exchange -> 404.
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent("exch-unknown", certificateId, "ACCEPTED", false)))
                .andExpect(status().isNotFound());

        // REJECTED without errors -> 400.
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", false)))
                .andExpect(status().isBadRequest());
    }

    private static String acceptanceEvent(String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
        String errors = withErrors ? ",\"errors\":[{\"message\":\"bad\"}]" : "";
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:BPNL0000000002CD",
                  "id": "11111111-1111-1111-1111-111111111111",
                  "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "%s"%s }
                }
                """.formatted(exchangeId, certificateId, acceptanceStatus, errors);
    }
}
