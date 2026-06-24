package org.metaform.certo.provider;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.Document;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
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

    @Autowired
    ProviderDocumentStore documentStore;

    @Test
    void requestOfferedType_fulfilledImmediately_andPollable() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"certifiedLocationBpns\":[\"BPNS00000003AYRE\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.certificateId").value("cert-iso9001-0001"))
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn();

        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        mvc.perform(get("/certificate-requests/{id}", exchangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.revision").value(2))
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
    void retrieveCertificate_returnsJsonMetadataWithDocumentReferences() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-iso9001-0001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.certificateId").value("cert-iso9001-0001"))
                .andExpect(jsonPath("$.revision").value(2)) // latest by default
                .andExpect(jsonPath("$.certificateType").value("ISO9001"))
                .andExpect(jsonPath("$.trustLevel").value("high"))
                .andExpect(jsonPath("$.certifiedLocations[0].bpns").value("BPNS00000003AYRE"))
                .andExpect(jsonPath("$.certifiedLocations[0].locationRole").value("MAIN_LOCATION"))
                .andExpect(jsonPath("$.documents[0].documentId").isNotEmpty())
                .andExpect(jsonPath("$.documents[0].mediaType").value("application/pdf"));
    }

    @Test
    void retrieveDocument_returnsBinaryWithItsMediaType() throws Exception {
        var metadata = mapper.readTree(mvc.perform(get("/certificates/{id}", "cert-iso9001-0001"))
                .andReturn().getResponse().getContentAsString());
        var documentId = metadata.get("documents").get(0).get("documentId").asString();

        var result = mvc.perform(get("/documents/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void retrieveDocument_unknown_notFound() throws Exception {
        mvc.perform(get("/documents/{id}", "doc-nope")).andExpect(status().isNotFound());
    }

    @Test
    void retrieveCertificate_specificRevision() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-iso9001-0001").param("revision", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void retrieveCertificate_unknown_notFound() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void search_byType_returnsLatestRevision() throws Exception {
        mvc.perform(post("/certificates/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("ISO9001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].certificateId").value("cert-iso9001-0001"))
                .andExpect(jsonPath("$[0].revision").value(2));
    }

    @Test
    void search_byCertifiedLocationBpns_matches() throws Exception {
        mvc.perform(post("/certificates/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$condition\":{\"$match\":[{\"$field\":\"certifiedLocations.bpns\",\"$eq\":\"BPNS00000003AYRE\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void search_unsupportedField_notImplemented() throws Exception {
        mvc.perform(post("/certificates/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$condition\":{\"$match\":[{\"$field\":\"registrationNumber\",\"$eq\":\"x\"}]}}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void acceptanceNotification_recordsStatus_thenUnknownIs404_andRejectedNeedsErrors() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent("exch-unknown", certificateId, "ACCEPTED", false)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceNotification_directTerminalWithoutRetrieved_isRecorded() throws Exception {
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
    void acceptanceNotification_rejectedWithPerSiteSpecifier_isRecorded() throws Exception {
        // CX-0135 errors[] may carry a per-site `specifier` (e.g. a BPNS) scoping the error.
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:BPNL0000000002CD",
                  "sourcebpn": "BPNL0000000002CD",
                  "id": "acc-specifier-%s",
                  "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "REJECTED",
                            "errors": [ {"message": "Certificate has expired"},
                                        {"specifier": "BPNS000000000002", "message": "Site rejected"} ] }
                }
                """.formatted(exchangeId, exchangeId, certificateId);
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json").content(event))
                .andExpect(status().isNoContent());

        var view = exchangeView(exchangeId);
        assertThat(view.get("acceptanceStatus").asString()).isEqualTo("REJECTED");
        assertThat(view.get("acceptanceErrors").get(1).get("specifier").asString()).isEqualTo("BPNS000000000002");
    }

    @Test
    void request_notHeld_acknowledgesThenFulfillsAsynchronously() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"IATF16949\",\"certifiedLocationBpns\":[\"BPNS-NEW-SITE\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        var exchangeId = body.get("exchangeId").asString();
        var certificateId = body.get("certificateId").asString();

        mvc.perform(get("/certificates/{id}", certificateId)).andExpect(status().isNotFound());

        assertThat(advance(exchangeId)).isEqualTo("CERTIFICATION_REQUESTED");
        assertThat(advance(exchangeId)).isEqualTo("FULFILLED");
        assertThat(requestStatus(exchangeId)).isEqualTo("FULFILLED");

        mvc.perform(get("/certificates/{id}", certificateId)).andExpect(status().isOk());
    }

    @Test
    void request_failTrigger_endsInFailed() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"certifiedLocationBpns\":[\"BPNFAIL\"]}"))
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
                        .content("{\"certificateType\":\"ISO9001\",\"certifiedLocationBpns\":[\"BPNS-NEW-SITE\"]}"))
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
    void modify_publishesNewRevision_servedAndSearchable() throws Exception {
        seedCertificate("cert-mod-test", "MODTEST");

        mvc.perform(post("/certificates/{id}/modify", "cert-mod-test"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.lifecycleStatus").value("MODIFIED"));

        mvc.perform(get("/certificates/{id}", "cert-mod-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post("/certificates/search").contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("MODTEST")))
                .andExpect(jsonPath("$[0].revision").value(2));
    }

    @Test
    void withdraw_returnsWithdrawnStatusBody_excludedFromSearch_andSecondWithdrawConflicts() throws Exception {
        seedCertificate("cert-wd-test", "WDTEST");
        mvc.perform(get("/certificates/{id}", "cert-wd-test")).andExpect(status().isOk());

        mvc.perform(post("/certificates/{id}/withdraw", "cert-wd-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("WITHDRAWN"));

        // A withdrawn certificate stays observable: 200 with the minimal withdrawn status body (CX-0135 §3.3.2).
        mvc.perform(get("/certificates/{id}", "cert-wd-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.certifiedLocations").doesNotExist());

        mvc.perform(post("/certificates/search").contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("WDTEST")))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/certificates/{id}/withdraw", "cert-wd-test")).andExpect(status().isConflict());
    }

    private void seedCertificate(String certificateId, String type) {
        var cert = new Certificate(certificateId, type, "2015", "REG-" + certificateId, "high", null,
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000009", "BPNS00000009ZZZZ", LocationRole.MAIN_LOCATION)),
                null, null);
        var documentId = "doc-" + certificateId + "-r1";
        documentStore.save(new Document(documentId, LocalDate.of(2024, 1, 1), "en", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
        cert.addRevision(new CertificateRevision(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), List.of(documentId)));
        certificateStore.save(cert);
    }

    @Test
    void acceptanceEvent_missingSourcebpn_isBadRequest() throws Exception {
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
        post400("{\"specversion\":\"2.0\"," + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e1\"," + data + "}");
        post400("{" + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e2\"," + data + "}");
        post400("{\"specversion\":\"1.0\"," + t + ",\"sourcebpn\":\"BPN\",\"id\":\"e3\"," + data + "}");
        post400("{\"specversion\":\"1.0\"," + t + ",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\"," + data + "}");
        post400("{\"specversion\":\"1.0\",\"source\":\"urn:bpn:x\",\"sourcebpn\":\"BPN\",\"id\":\"e5\"," + data + "}");
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

        var batch = "[" + acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)
                + "," + acceptanceEvent(exchangeId, certificateId, "REJECTED", false) + "]";
        mvc.perform(post("/certificate-acceptance-notifications")
                        .contentType("application/cloudevents+json").content(batch))
                .andExpect(status().isBadRequest());

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

        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(id, exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void search_paginates_withNextPrevLinks() throws Exception {
        for (int i = 1; i <= 3; i++) {
            seedCertificate("cert-paged-000" + i, "PAGED");
        }

        var link = mvc.perform(post("/certificates/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("limit", "1")
                        .content(searchByType("PAGED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getHeader("Link");

        assertThat(link).contains("rel=\"next\"");
        assertThat(link).doesNotContain("rel=\"prev\""); // first page
    }

    /** Opens an exchange for a held certificate -> immediately FULFILLED. Returns the exchangeId. */
    private String openExchange() throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"ISO9001\",\"certifiedLocationBpns\":[\"BPNS00000003AYRE\"]}"))
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

    private static String searchByType(String type) {
        return "{\"$condition\":{\"$match\":[{\"$field\":\"certificateType\",\"$eq\":\"" + type + "\"}]}}";
    }

    private static String acceptanceEvent(String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
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
