package org.metaform.certo.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.metaform.certo.testsupport.ManagementTestAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication and scope authorization on the management surface: every {@code /management/**} call
 * needs a valid bearer (401 otherwise), each endpoint needs its {@code certo-mgmt-api:<resource>:<action>}
 * scope (403 otherwise, including read-scope-on-write-endpoint), and {@code certo-mgmt-api:admin}
 * supersedes every fine-grained scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ManagementTestAuth.class)
@ExtendWith(OutputCaptureExtension.class)
class ManagementApiAuthorizationTest {

    private static final String PARTICIPANT_READ = "certo-mgmt-api:participant:read";
    private static final String PARTICIPANT_WRITE = "certo-mgmt-api:participant:write";
    private static final String CONSUMER_READ = "certo-mgmt-api:consumer:read";
    private static final String PROVIDER_READ = "certo-mgmt-api:provider:read";
    private static final String PROVIDER_WRITE = "certo-mgmt-api:provider:write";

    @Autowired
    MockMvc mvc;

    @Test
    void withoutToken_is401_andLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/management/v1/participant-contexts"))
                .andExpect(status().isUnauthorized());
        assertThat(output).contains("401 GET /management/v1/participant-contexts");
    }

    @Test
    void withMalformedToken_is401_andDecodeFailureIsLogged(CapturedOutput output) throws Exception {
        // Decode failures take a different path than missing tokens (the bearer filter's failure
        // handler, not the chain entry point) — both must log.
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        assertThat(output).contains("401 GET /management/v1/participant-contexts");
    }

    @Test
    void matchingScope_isAuthorized() throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", bearer(PARTICIPANT_READ)))
                .andExpect(status().isOk());
    }

    @Test
    void foreignScope_is403_andLoggedWithCallerAndScopes(CapturedOutput output) throws Exception {
        mvc.perform(get("/management/v1/participant-contexts")
                        .header("Authorization", bearer(CONSUMER_READ, PROVIDER_READ, PROVIDER_WRITE)))
                .andExpect(status().isForbidden());
        assertThat(output).contains("403 GET /management/v1/participant-contexts")
                .contains("sub=mgmt-test-client")
                .contains("SCOPE_" + CONSUMER_READ);
    }

    @Test
    void readScope_doesNotAllowWrites() throws Exception {
        mvc.perform(post("/management/v1/participant-contexts")
                        .header("Authorization", bearer(PARTICIPANT_READ))
                        .contentType("application/json")
                        .content("{\"bpn\":\"BPNL0000000009XX\",\"source\":\"urn:bpn:BPNL0000000009XX\",\"did\":\"did:web:authz-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actionWideReadScope_encompassesAllReadScopes() throws Exception {
        var read = bearer("certo-mgmt-api:read");
        mvc.perform(get("/management/v1/participant-contexts").header("Authorization", read))
                .andExpect(status().isOk());
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-provider/certificate-requests/query")
                        .header("Authorization", read)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/query")
                        .header("Authorization", read))
                .andExpect(status().isOk());
        // read-wide is still not write
        mvc.perform(post("/management/v1/participant-contexts")
                        .header("Authorization", read)
                        .contentType("application/json")
                        .content("{\"bpn\":\"BPNL0000000007XX\",\"source\":\"urn:bpn:BPNL0000000007XX\",\"did\":\"did:web:authz-wide\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actionWideWriteScope_encompassesAllWriteScopes() throws Exception {
        var write = bearer("certo-mgmt-api:write");
        mvc.perform(post("/management/v1/participant-contexts")
                        .header("Authorization", write)
                        .contentType("application/json")
                        .content("{\"bpn\":\"BPNL0000000006XX\",\"source\":\"urn:bpn:BPNL0000000006XX\",\"did\":\"did:web:authz-write-wide\"}"))
                .andExpect(status().isCreated());
        // write-wide is still not read
        mvc.perform(get("/management/v1/participant-contexts").header("Authorization", write))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminScope_supersedesEverything() throws Exception {
        var admin = "Bearer " + ManagementTestAuth.adminToken();
        mvc.perform(post("/management/v1/participant-contexts")
                        .header("Authorization", admin)
                        .contentType("application/json")
                        .content("{\"bpn\":\"BPNL0000000008XX\",\"source\":\"urn:bpn:BPNL0000000008XX\",\"did\":\"did:web:authz-admin\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/management/v1/participant-contexts").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void scopesGuardTheConsumerAndProviderSurfaces() throws Exception {
        // Provider query under provider:read passes authorization (404 would mean an unknown tenant, not a
        // denied call — the seeded provider context exists, so it is a plain 200).
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-provider/certificate-requests/query")
                        .header("Authorization", bearer(PROVIDER_READ))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // The same call under consumer scopes only is 403.
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-provider/certificate-requests/query")
                        .header("Authorization", bearer(CONSUMER_READ))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        // Consumer reconciliation query under consumer:read passes; participant scopes do not help there.
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/query")
                        .header("Authorization", bearer(CONSUMER_READ)))
                .andExpect(status().isOk());
        mvc.perform(post("/management/v1/participant-contexts/pctx-seed-consumer/consumer/exchanges/query")
                        .header("Authorization", bearer(PARTICIPANT_READ, PARTICIPANT_WRITE)))
                .andExpect(status().isForbidden());
    }

    private static String bearer(String... scopes) {
        return "Bearer " + ManagementTestAuth.token(scopes);
    }
}
