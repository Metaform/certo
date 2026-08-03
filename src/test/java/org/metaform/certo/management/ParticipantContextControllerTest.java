package org.metaform.certo.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.metaform.certo.testsupport.MockSigletConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /management/v1/participant-contexts}: the id is server-generated when omitted, or caller-chosen
 * (URL-safe, unique) when supplied.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockSigletConfig.class)
class ParticipantContextControllerTest {

    @Autowired
    MockMvc mvc;

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void omittedId_isGeneratedUuid() throws Exception {
        mvc.perform(create(null, "BPNL000000000P01", "urn:bpn:BPNL000000000P01", "did:web:pctx-gen"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantContextId").value(org.hamcrest.Matchers.matchesPattern(UUID_PATTERN)));
    }

    @Test
    void suppliedId_isUsedVerbatim() throws Exception {
        mvc.perform(create("tenant-acme", "BPNL000000000P02", "urn:bpn:BPNL000000000P02", "did:web:pctx-acme"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantContextId").value("tenant-acme"));

        // It is then retrievable under exactly that id.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/management/v1/participant-contexts/{id}", "tenant-acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.did").value("did:web:pctx-acme"));
    }

    @Test
    void duplicateSuppliedId_isConflict() throws Exception {
        mvc.perform(create("tenant-dup", "BPNL000000000P03", "urn:bpn:BPNL000000000P03", "did:web:pctx-dup-1"))
                .andExpect(status().isCreated());
        // Same id, different did -> the id clash is a conflict.
        mvc.perform(create("tenant-dup", "BPNL000000000P03", "urn:bpn:BPNL000000000P03", "did:web:pctx-dup-2"))
                .andExpect(status().isConflict());
    }

    @Test
    void list_returnsAllRegisteredContexts() throws Exception {
        mvc.perform(create("tenant-list-1", "BPNL000000000P05", "urn:bpn:BPNL000000000P05", "did:web:pctx-list-1"))
                .andExpect(status().isCreated());
        mvc.perform(create("tenant-list-2", "BPNL000000000P06", "urn:bpn:BPNL000000000P06", "did:web:pctx-list-2"))
                .andExpect(status().isCreated());

        // GET on the collection root returns a JSON array including every registered context (both just-created
        // tenants and the seeded provider/consumer ones).
        mvc.perform(get("/management/v1/participant-contexts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.participantContextId=='tenant-list-1')].did")
                        .value(org.hamcrest.Matchers.hasItem("did:web:pctx-list-1")))
                .andExpect(jsonPath("$[?(@.participantContextId=='tenant-list-2')].did")
                        .value(org.hamcrest.Matchers.hasItem("did:web:pctx-list-2")));
    }

    @Test
    void invalidSuppliedId_isBadRequest() throws Exception {
        mvc.perform(create("bad id/with slash", "BPNL000000000P04", "urn:bpn:BPNL000000000P04", "did:web:pctx-bad"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.RequestBuilder create(String id, String bpn, String source, String did) {
        var idField = id == null ? "" : "\"participantContextId\":\"" + id + "\",";
        var body = "{" + idField + "\"bpn\":\"" + bpn + "\",\"source\":\"" + source + "\",\"did\":\"" + did + "\"}";
        return post("/management/v1/participant-contexts").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
