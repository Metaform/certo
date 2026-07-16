package org.metaform.certo.management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /management/v1/participant-contexts}: the id is server-generated when omitted, or caller-chosen
 * (URL-safe, unique) when supplied.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
