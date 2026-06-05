package org.metaform.certo.provider;

import org.junit.jupiter.api.Test;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateVersion;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProviderCertificateApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ProviderCertificateStore certificateStore;

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

        // RFC 2387: the Content-Type names the root part via `start`, and each part has a Content-ID.
        assertThat(result.getResponse().getHeader("Content-Type")).contains("start=\"<metadata@certo>\"");
        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("Content-Type: application/json");
        assertThat(body).contains("Content-ID: <metadata@certo>");
        assertThat(body).contains("\"certificateId\":\"cert-iso9001-0001\"");
        assertThat(body).contains("\"version\":2"); // latest by default
        assertThat(body).contains("Content-Type: application/pdf");
        assertThat(body).contains("Content-ID: <certificate@certo>");
        assertThat(body).contains("%PDF-1.4");
    }

    @Test
    void retrieveCertificate_multipartAccept_ok_butIncompatibleAccept_is406() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-iso9001-0001")
                        .accept(MediaType.parseMediaType("multipart/related")))
                .andExpect(status().isOk());

        mvc.perform(get("/certificates/{id}", "cert-iso9001-0001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
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
    void retrieveCertificate_unknown_withMultipartAccept_stillNotFound() throws Exception {
        // The consumer sends Accept: multipart/related; the JSON error must still be returned (404, not 500).
        mvc.perform(get("/certificates/{id}", "cert-nope")
                        .accept(MediaType.parseMediaType("multipart/related")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
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
        // Open an exchange for a held certificate -> immediately FULFILLED, so acceptance is allowed.
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

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

    @Test
    void acceptanceNotification_directTerminalWithoutRetrieved_isRecorded() throws Exception {
        // RETRIEVED is optional (CX-0135 §2.1.3): a terminal verdict may be reported straight from
        // FULFILLED with no prior RETRIEVED. This is the path Certo's consumer takes.
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptanceNotification_optionalRetrievedThenTerminal_isRecorded() throws Exception {
        // The optional RETRIEVED receipt remains valid: FULFILLED -> RETRIEVED -> ACCEPTED.
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "RETRIEVED", false)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("RETRIEVED");

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void request_notHeld_acknowledgesThenFulfillsAsynchronously() throws Exception {
        // A type+location not already held -> ACKNOWLEDGED, then advances to FULFILLED.
        // (IATF16949 so the published produced certificate doesn't pollute the ISO9001 query assertion.)
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"IATF16949\",\"locationBpns\":[\"BPNS-NEW-SITE\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var exchangeId = body.get("exchangeId").asString();
        var certificateId = body.get("certificateId").asString();

        // Not retrievable until published (FULFILLED).
        mvc.perform(get("/certificates/{id}", certificateId)).andExpect(status().isNotFound());

        assertThat(advance(exchangeId)).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(advance(exchangeId)).isEqualTo("FULFILLED");
        assertThat(requestStatus(exchangeId)).isEqualTo("FULFILLED");

        // Now published and retrievable.
        mvc.perform(get("/certificates/{id}", certificateId)).andExpect(status().isOk());
    }

    @Test
    void request_failTrigger_endsInFailed() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"locationBpns\":[\"BPNFAIL\"]}"))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andReturn();
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        assertThat(advance(exchangeId)).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(advance(exchangeId)).isEqualTo("FAILED");
        mvc.perform(get("/certificate-requests/{id}", exchangeId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void acceptance_beforeFulfilled_isConflict() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"locationBpns\":[\"BPNS-NEW-SITE\"]}"))
                .andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var exchangeId = body.get("exchangeId").asString(); // ACKNOWLEDGED, not yet FULFILLED
        var certificateId = body.get("certificateId").asString();

        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptance_afterTerminal_isConflict() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        // A new (non-duplicate) event trying to change a terminal acceptance -> 409.
        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isConflict());
    }

    @Test
    void reattempt_opensADistinctExchange() throws Exception {
        var first = openExchange();
        var second = openExchange();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void modify_publishesNewVersion_servedAndQueryable() throws Exception {
        seedCertificate("cert-mod-test", "MODTEST");

        mvc.perform(post("/certificates/{id}/modify", "cert-mod-test"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lifecycleStatus").value("MODIFIED"));

        mvc.perform(get("/certificates/{id}", "cert-mod-test")).andExpect(status().isOk());
        mvc.perform(post("/certificates/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"MODTEST\"}"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void withdraw_makesUnretrievableAndExcludedFromQuery_andSecondWithdrawConflicts() throws Exception {
        seedCertificate("cert-wd-test", "WDTEST");
        mvc.perform(get("/certificates/{id}", "cert-wd-test")).andExpect(status().isOk());

        mvc.perform(post("/certificates/{id}/withdraw", "cert-wd-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("WITHDRAWN"));

        mvc.perform(get("/certificates/{id}", "cert-wd-test")).andExpect(status().isNotFound());
        mvc.perform(post("/certificates/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"WDTEST\"}"))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/certificates/{id}/withdraw", "cert-wd-test")).andExpect(status().isConflict());
    }

    private void seedCertificate(String certificateId, String type) {
        var cert = new Certificate(certificateId, "ds-" + certificateId, type, List.of());
        cert.addVersion(new CertificateVersion(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), new byte[]{'%', 'P', 'D', 'F'}));
        certificateStore.save(cert);
    }

    @Test
    void acceptanceEvent_missingSourcebpn_isBadRequest() throws Exception {
        // Required CX-0000 extension attribute 'sourcebpn' is absent -> envelope validation fails (400).
        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:BPNL0000000002CD",
                  "id": "no-sourcebpn-1",
                  "data": { "exchangeId": "exch-x", "certificateId": "cert-x", "status": "ACCEPTED" }
                }
                """;
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json").content(event))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceEvent_invalidEnvelope_isBadRequest() throws Exception {
        var data = "\"data\":{\"exchangeId\":\"x\",\"certificateId\":\"c\",\"status\":\"ACCEPTED\"}";
        var t = "\"type\":\"org.catena-x.ccm.CertificateAcceptanceStatus.v1\"";
        post400("{\"specversion\":\"2.0\"," + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e1\"," + data + "}"); // wrong specversion
        post400("{" + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e2\"," + data + "}");                        // missing specversion
        post400("{\"specversion\":\"1.0\"," + t + ",\"sourcebpn\":\"BPN\",\"id\":\"e3\"," + data + "}");                        // missing source
        post400("{\"specversion\":\"1.0\"," + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\"," + data + "}");            // missing id
        post400("{\"specversion\":\"1.0\",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e5\"," + data + "}");        // missing type
    }

    private void post400(String event) throws Exception {
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json").content(event))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceBatch_isAtomic_oneBadEventAppliesNone() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        // Batch: a valid ACCEPTED followed by an invalid REJECTED (no errors). The whole batch -> 400.
        var batch = "[" + acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)
                + "," + acceptanceEvent(exchangeId, certificateId, "REJECTED", false) + "]";
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json").content(batch))
                .andExpect(status().isBadRequest());

        // The valid event in the batch must NOT have been applied.
        assertThat(exchangeView(exchangeId).hasNonNull("acceptanceStatus")).isFalse();
    }

    @Test
    void acceptanceEvent_duplicateIsIgnored() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();
        var id = "dup-" + exchangeId;

        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(id, exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");

        // Same source+id, different status -> ignored as a duplicate; status stays ACCEPTED.
        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(id, exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void query_paginates_withNextFirstLastLinks() throws Exception {
        // Seed three certificates of a dedicated type so a limit=1 query paginates.
        for (int i = 1; i <= 3; i++) {
            var cert = new Certificate("cert-paged-000" + i, "ds-paged", "PAGED", List.of());
            cert.addVersion(new CertificateVersion(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), new byte[0]));
            certificateStore.save(cert);
        }

        var link = mvc.perform(post("/certificates/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"PAGED\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getHeader("Link");

        assertThat(link).contains("rel=\"next\"").contains("rel=\"first\"").contains("rel=\"last\"");
        assertThat(link).doesNotContain("rel=\"prev\""); // first page
    }

    /** Opens an exchange for a held certificate -> immediately FULFILLED. Returns the exchangeId. */
    private String openExchange() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"locationBpns\":[\"BPNS00000003AYRE\"]}"))
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();
    }

    private String requestStatus(String exchangeId) throws Exception {
        var result = mvc.perform(get("/certificate-requests/{id}", exchangeId)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("status").asString();
    }

    private String advance(String exchangeId) throws Exception {
        var result = mvc.perform(post("/certificate-requests/{id}/advance", exchangeId)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("status").asString();
    }

    private JsonNode exchangeView(String exchangeId) throws Exception {
        var result = mvc.perform(get("/certificate-exchanges/{id}", exchangeId)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String acceptanceEvent(String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
        // Unique CloudEvent id per call so the idempotency layer doesn't dedupe distinct events.
        return acceptanceEvent("acc-" + exchangeId + "-" + acceptanceStatus, exchangeId, certificateId, acceptanceStatus, withErrors);
    }

    private static String acceptanceEvent(String id, String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
        String errors = withErrors ? ",\"errors\":[{\"message\":\"bad\"}]" : "";
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:BPNL0000000002CD",
                  "sourcebpn": "BPNL0000000002CD",
                  "id": "%s",
                  "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "%s"%s }
                }
                """.formatted(id, exchangeId, certificateId, acceptanceStatus, errors);
    }
}
