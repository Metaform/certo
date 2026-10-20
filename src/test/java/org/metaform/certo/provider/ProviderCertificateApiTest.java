package org.metaform.certo.provider;

import org.metaform.certo.testsupport.MockMvcTokenConfig;
import org.metaform.certo.testsupport.TestTenants;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.provider.domain.Certificate;
import org.metaform.certo.provider.domain.CertificateRevision;
import org.metaform.certo.provider.domain.Document;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
@Import(MockMvcTokenConfig.class)
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
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"iso9001\",\"certifiedLocations\":[{\"siteId\":\"SITE00000003AYRE\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.certificateId").value(TestTenants.ISO9001_CERT_ID))
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn();

        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        mvc.perform(get("/exchanges/{id}", exchangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("FULFILLED"));
    }

    @Test
    void declineRequest_endsInDeclined() throws Exception {
        // Any type is accepted; a not-held request waits for the backend, which may decline it.
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"iso50001\",\"certifiedLocations\":[{\"siteId\":\"SITE-DECLINE\"}]}"))
                .andExpect(jsonPath("$.status").value("CERTIFICATE_REQUESTED"))
                .andReturn();
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/{id}/decline", exchangeId).param("flowId", "flow-1").param("reason", "not a customer"))
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mvc.perform(get("/exchanges/{id}", exchangeId))
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void requestStatus_unknownExchange_notFound() throws Exception {
        mvc.perform(get("/exchanges/{id}", "exch-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void requestMissingType_badRequest() throws Exception {
        mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retrieveCertificate_returnsJsonMetadataWithDocumentReferences() throws Exception {
        mvc.perform(get("/certificates/{id}", TestTenants.ISO9001_CERT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.certificateId").value(TestTenants.ISO9001_CERT_ID))
                .andExpect(jsonPath("$.revision").value(2)) // latest by default
                .andExpect(jsonPath("$.certificateType").value("iso9001"))
                .andExpect(jsonPath("$.certifiedLocations[0].siteId").value("SITE00000003AYRE"))
                .andExpect(jsonPath("$.certifiedLocations[0].locationRole").value("MAIN_LOCATION"))
                .andExpect(jsonPath("$.documents[0].documentId").isNotEmpty())
                .andExpect(jsonPath("$.documents[0].mediaType").value("application/pdf"));
    }

    @Test
    void retrieveDocument_returnsBinaryWithItsMediaType() throws Exception {
        var metadata = mapper.readTree(mvc.perform(get("/certificates/{id}", TestTenants.ISO9001_CERT_ID))
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
    void retrieveCertificate_unknown_notFound() throws Exception {
        mvc.perform(get("/certificates/{id}", "cert-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void search_byType_returnsLatestRevision() throws Exception {
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("iso9001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].certificateId").value(TestTenants.ISO9001_CERT_ID))
                .andExpect(jsonPath("$.result[0].revision").value(2));
    }

    @Test
    void search_byCertifiedLocationSiteId_matches() throws Exception {
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certifiedLocations\":[{\"siteId\":\"SITE00000003AYRE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void search_unsupportedFilterProperty_isBadRequest() throws Exception {
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"x\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certifiedLocations\":[{\"notAProperty\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceNotification_recordsStatus_thenUnknownIs404_andRejectedNeedsErrors() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent("exch-unknown", certificateId, "ACCEPTED", false)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceNotification_directTerminalWithoutRetrieved_isRecorded() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptanceNotification_duplicateReReport_isAcknowledgedWithoutTransition() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        // A re-report of the current state (fresh event id) is a duplicate: 204, no transition (SCE).
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");

        // A transition out of a terminal state is invalid: 409 (SCE).
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptanceNotification_rejectedWithPerSiteSpecifier_isRecorded() throws Exception {
        // the SCE specification errors[] may carry a per-site `specifier` (e.g. a SITE) scoping the error.
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateAcceptanceStatus.v1",
                  "source": "did:web:consumer",
                  "id": "acc-specifier-%s",
                  "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "REJECTED",
                            "errors": [ {"message": "Certificate has expired"},
                                        {"specifier": "SITE000000000002", "message": "Site rejected"} ] }
                }
                """.formatted(exchangeId, exchangeId, certificateId);
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json").content(event))
                .andExpect(status().isNoContent());

        var view = exchangeView(exchangeId);
        assertThat(view.get("acceptanceStatus").asString()).isEqualTo("REJECTED");
        assertThat(view.get("acceptanceErrors").get(1).get("specifier").asString()).isEqualTo("SITE000000000002");
    }

    @Test
    void request_notHeld_waitsThenFulfillsWhenCertificateAdded() throws Exception {
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"iatf16949\",\"certifiedLocations\":[{\"siteId\":\"SITE-NEW-SITE\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CERTIFICATE_REQUESTED"))
                .andReturn();
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        // The backend issues the certificate (state only); the client discovers the waiting exchange it
        // covers (UC2) and fulfills it per-exchange (over that consumer's flow).
        var added = addCertificate("iatf16949", "SITE-NEW-SITE");
        var certificateId = added.get("certificateId").asString();
        mvc.perform(get("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/{id}/fulfillable-requests", certificateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.items[0].counterpartyId").exists());
        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/{id}/fulfill", exchangeId).param("flowId", "flow-1"))
                .andExpect(status().isOk());

        assertThat(requestStatus(exchangeId)).isEqualTo("FULFILLED");
        mvc.perform(get("/certificates/{id}", certificateId)).andExpect(status().isOk());
    }

    @Test
    void queryPendingRequests_returnsWaitingExchange() throws Exception {
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"uc1querytype\",\"certifiedLocations\":[{\"siteId\":\"SITE-Q-1\"}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        // UC1 request-centric query: filter the backlog by the (unique) type and find the waiting exchange.
        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"uc1querytype\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].exchangeId").value(exchangeId))
                .andExpect(jsonPath("$.items[0].counterpartyId").exists())
                .andExpect(jsonPath("$.items[0].status").value("CERTIFICATE_REQUESTED"));
    }

    @Test
    void request_backendCannotIssue_endsInDeclined() throws Exception {
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"iso9001\",\"certifiedLocations\":[{\"siteId\":\"SITE-NEW-SITE\"}]}"))
                .andExpect(jsonPath("$.status").value("CERTIFICATE_REQUESTED"))
                .andReturn();
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        // A backend that cannot issue declines the exchange; the grounds travel in the errors array (SCE).
        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-requests/{id}/decline", exchangeId).param("flowId", "flow-1").param("reason", "authority rejected"))
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mvc.perform(get("/exchanges/{id}", exchangeId))
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void acceptance_beforeFulfilled_isConflict() throws Exception {
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"iso9001\",\"certifiedLocations\":[{\"siteId\":\"SITE-NEW-SITE\"}]}"))
                .andReturn();
        // CERTIFICATE_REQUESTED — not yet FULFILLED, and no certificate assigned yet.
        var exchangeId = mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();

        mvc.perform(post("/notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, "cert-not-yet-issued", "ACCEPTED", false)))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptance_afterTerminal_isConflict() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        mvc.perform(post("/notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isConflict());
    }

    @Test
    void nonConformantCertificateTypeCode_isRejected() throws Exception {
        // Type codes MUST be lowercase Latin letters and digits only (SCE spelling rules) — on the wire...
        mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + java.util.UUID.randomUUID() + "\",\"certificateType\":\"ISO9001\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"iso 9001\"}"))
                .andExpect(status().isBadRequest());
        // ...and at management ingestion.
        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"iso_9001\",\"validFrom\":\"2026-01-01\",\"validUntil\":\"2029-01-01\","
                                + "\"certifiedLocations\":[{\"legalEntityId\":\"LE-X\",\"locationRole\":\"MAIN_LOCATION\"}],\"documentIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repeatedOpen_withTheSameMessageId_returnsTheSameExchange() throws Exception {
        // Exchange creation is idempotent on (counterparty, messageId): a retried request repeating the pair
        // resolves to the already-created exchange rather than opening a duplicate.
        var messageId = java.util.UUID.randomUUID().toString();
        var first = openExchange(messageId);
        var second = openExchange(messageId);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void fulfill_withoutFlowId_isBadRequest() throws Exception {
        // requireFlow: a management operation that pushes outbound must carry a flowId (checked before the
        // exchange is loaded), so a missing flowId is a 400 regardless of the exchange id.
        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX
                        + "/certificate-requests/{id}/fulfill", "exch-anything"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revise_appendsNewRevision_servedAndSearchable() throws Exception {
        seedCertificate("cert-mod-test", "modtest");
        var docResult = mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}"))
                .andExpect(status().isCreated()).andReturn();
        var documentId = mapper.readTree(docResult.getResponse().getContentAsString()).get("documentId").asString();

        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/{id}/revisions", "cert-mod-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":\"2026-01-01\",\"validUntil\":\"2029-01-01\",\"documentIds\":[\"" + documentId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.lifecycleStatus").value("MODIFIED"));

        mvc.perform(get("/certificates/{id}", "cert-mod-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("modtest")))
                .andExpect(jsonPath("$.result[0].revision").value(2));
    }

    @Test
    void withdraw_returnsWithdrawnStatusBody_excludedFromSearch_andSecondWithdrawConflicts() throws Exception {
        seedCertificate("cert-wd-test", "wdtest");
        mvc.perform(get("/certificates/{id}", "cert-wd-test")).andExpect(status().isOk());

        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/{id}/withdraw", "cert-wd-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("WITHDRAWN"));

        // A withdrawn certificate stays observable: 200 with the minimal withdrawn status body.
        mvc.perform(get("/certificates/{id}", "cert-wd-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.certifiedLocations").doesNotExist());

        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON)
                        .content(searchByType("wdtest")))
                .andExpect(jsonPath("$.result.length()").value(0));

        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates/{id}/withdraw", "cert-wd-test")).andExpect(status().isConflict());
    }

    private void seedCertificate(String certificateId, String type) {
        var cert = new Certificate(certificateId, TestTenants.PROVIDER_PCTX, type, "2015", "REG-" + certificateId, null,
                List.of(new CertifiedLocation("LE00000001AXS", "SITE00000009ZZZZ", "ADDR000000000009", LocationRole.MAIN_LOCATION)),
                null, null);
        var documentId = "doc-" + certificateId + "-r1";
        documentStore.save(new Document(documentId, TestTenants.PROVIDER_PCTX, LocalDate.of(2024, 1, 1), "en", "application/pdf", new byte[]{'%', 'P', 'D', 'F'}));
        cert.addRevision(new CertificateRevision(1, LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), List.of(documentId)));
        certificateStore.save(cert);
    }

    @Test
    void acceptanceEvent_sourceNotMatchingTheAuthenticatedSender_isRejected() throws Exception {
        var event = """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateAcceptanceStatus.v1",
                  "source": "did:web:somebody-else",
                  "id": "bad-source-1",
                  "data": { "exchangeId": "exch-x", "certificateId": "cert-x", "status": "ACCEPTED" }
                }
                """;
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json")
                        .content(event))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceEvent_invalidEnvelope_isBadRequest() throws Exception {
        var data = "\"data\":{\"exchangeId\":\"x\",\"certificateId\":\"c\",\"status\":\"ACCEPTED\"}";
        var t = "\"type\":\"org.dsaf.sce.CertificateAcceptanceStatus.v1\"";
        post400("{\"specversion\":\"2.0\"," + t + ",\"source\":\"did:web:consumer\",\"id\":\"e1\"," + data + "}");
        post400("{" + t + ",\"source\":\"did:web:consumer\",\"id\":\"e2\"," + data + "}");
        post400("{\"specversion\":\"1.0\"," + t + ",\"id\":\"e3\"," + data + "}");
        post400("{\"specversion\":\"1.0\"," + t + ",\"source\":\"did:web:consumer\"," + data + "}");
        post400("{\"specversion\":\"1.0\",\"source\":\"did:web:consumer\",\"id\":\"e5\"," + data + "}");
    }

    private void post400(String event) throws Exception {
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json").content(event))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptanceBatch_isAtomic_oneBadEventAppliesNone() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();

        var batch = "[" + acceptanceEvent(exchangeId, certificateId, "ACCEPTED", false)
                + "," + acceptanceEvent(exchangeId, certificateId, "REJECTED", false) + "]";
        mvc.perform(post("/notifications")
                        .contentType("application/cloudevents+json").content(batch))
                .andExpect(status().isBadRequest());

        assertThat(exchangeView(exchangeId).hasNonNull("acceptanceStatus")).isFalse();
    }

    @Test
    void acceptanceEvent_duplicateIsIgnored() throws Exception {
        var exchangeId = openExchange();
        var certificateId = exchangeView(exchangeId).get("certificateId").asString();
        var id = "dup-" + exchangeId;

        mvc.perform(post("/notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(id, exchangeId, certificateId, "ACCEPTED", false)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");

        mvc.perform(post("/notifications").contentType("application/cloudevents+json")
                        .content(acceptanceEvent(id, exchangeId, certificateId, "REJECTED", true)))
                .andExpect(status().isNoContent());
        assertThat(exchangeView(exchangeId).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void search_paginates_withABodyCursor() throws Exception {
        for (int i = 1; i <= 3; i++) {
            seedCertificate("cert-paged-000" + i, "paged");
        }

        var first = mapper.readTree(mvc.perform(post("/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("limit", "1")
                        .content(searchByType("paged")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(first.get("result").size()).isEqualTo(1);
        assertThat(first.hasNonNull("cursor")).as("not the last page -> a cursor").isTrue();

        // Walk to the last page: it carries no cursor.
        var cursor = first.get("cursor").asString();
        var last = first;
        while (last.hasNonNull("cursor")) {
            last = mapper.readTree(mvc.perform(post("/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .param("limit", "1")
                            .param("cursor", last.get("cursor").asString())
                            .content(searchByType("paged")))
                    .andReturn().getResponse().getContentAsString());
        }
        assertThat(last.get("result").size()).isEqualTo(1);
        assertThat(cursor).isNotBlank();
    }

    /** Opens an exchange for a held certificate -> immediately FULFILLED. Returns the exchangeId. */
    private String openExchange() throws Exception {
        return openExchange(java.util.UUID.randomUUID().toString());
    }

    /** Opens (or, repeating a messageId, resolves) an exchange for a held certificate. */
    private String openExchange(String messageId) throws Exception {
        var result = mvc.perform(post("/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + messageId + "\",\"certificateType\":\"iso9001\",\"certifiedLocations\":[{\"siteId\":\"SITE00000003AYRE\"}]}"))
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("exchangeId").asString();
    }

    private String requestStatus(String exchangeId) throws Exception {
        var result = mvc.perform(get("/exchanges/{id}", exchangeId)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("status").asString();
    }

    /** Backend uploads a document, then issues a certificate referencing it; returns the add-certificate response. */
    private JsonNode addCertificate(String type, String location) throws Exception {
        var docResult = mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var documentId = mapper.readTree(docResult.getResponse().getContentAsString()).get("documentId").asString();

        var body = """
                {"certificateType":"%s","certificateTypeVersion":"2016","registrationNumber":"DE-CERT-0001",
                 "validFrom":"2020-01-01","validUntil":"2035-01-01",
                 "certifiedLocations":[{"legalEntityId":"LE000000TESTLE","addressId":"ADDR000000TESTAD","siteId":"%s","locationRole":"MAIN_LOCATION"}],
                 "issuer":{"issuerName":"TUV","issuerId":"LE000000ISSUER"},
                 "documentIds":["%s"]}""".formatted(type, location, documentId);
        var result = mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode exchangeView(String exchangeId) throws Exception {
        var result = mvc.perform(get("/management/v1/participant-contexts/" + TestTenants.PROVIDER_PCTX + "/certificate-exchanges/{id}", exchangeId)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String searchByType(String type) {
        return "{\"certificateType\":\"" + type + "\"}";
    }

    private static String acceptanceEvent(String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
        return acceptanceEvent("acc-" + exchangeId + "-" + acceptanceStatus, exchangeId, certificateId, acceptanceStatus, withErrors);
    }

    private static String acceptanceEvent(String id, String exchangeId, String certificateId, String acceptanceStatus, boolean withErrors) {
        String errors = withErrors ? ",\"errors\":[{\"message\":\"bad\"}]" : "";
        return """
                {
                  "specversion": "1.0",
                  "type": "org.dsaf.sce.CertificateAcceptanceStatus.v1",
                  "source": "did:web:consumer",
                  "id": "%s",
                  "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "%s"%s }
                }
                """.formatted(id, exchangeId, certificateId, acceptanceStatus, errors);
    }
}
