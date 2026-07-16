package org.metaform.certo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-tenancy is enforced with no exceptions: two provider tenants are created through the management API
 * (system-generated ids), each holding a certificate of the same type. A CCM protocol call is scoped to the
 * tenant its token audience resolves to — a request fulfils only from that tenant's holdings, a retrieval or
 * search never crosses the tenant boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockSigletConfig.class)
class MultiTenantIsolationTest {

    private static final String TYPE = "MT-SHARED-TYPE";
    private static final String LOCATION = "BPNS-MT-SITE-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockSiglet siglet;

    @Test
    void certificatesAndRequestsAreScopedToTheAddressedTenant() throws Exception {
        var tenantA = createContext("BPNL000000000AAA", "urn:bpn:BPNL000000000AAA", "did:web:tenant-a");
        var tenantB = createContext("BPNL000000000BBB", "urn:bpn:BPNL000000000BBB", "did:web:tenant-b");
        assertThat(tenantA.get("participantContextId").asString())
                .isNotEqualTo(tenantB.get("participantContextId").asString());

        var certA = addCertificate(tenantA.get("participantContextId").asString());
        var certB = addCertificate(tenantB.get("participantContextId").asString());
        assertThat(certA).isNotEqualTo(certB);

        // A request addressed to tenant A fulfils only from A's holdings — with A's certificate, never B's.
        var fulfilledForA = requestAs("did:web:tenant-a");
        assertThat(fulfilledForA.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(fulfilledForA.get("certificateId").asString()).isEqualTo(certA);

        var fulfilledForB = requestAs("did:web:tenant-b");
        assertThat(fulfilledForB.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(fulfilledForB.get("certificateId").asString()).isEqualTo(certB);

        // A's certificate is invisible to a caller addressed to tenant B (retrieval never crosses the boundary).
        mvc.perform(get("/certificates/{id}", certA).header("Authorization", bearer("did:web:tenant-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/certificates/{id}", certA).header("Authorization", bearer("did:web:tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certA));

        // Search is scoped too: tenant A sees exactly its own certificate for the shared type.
        var searchA = mapper.readTree(mvc.perform(post("/certificates/search")
                        .header("Authorization", bearer("did:web:tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(searchByType()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(searchA.size()).isEqualTo(1);
        assertThat(searchA.get(0).get("certificateId").asString()).isEqualTo(certA);
    }

    @Test
    void documentsAndAcceptanceAreScopedToTheAddressedTenant() throws Exception {
        var tenantC = createContext("BPNL000000000CCC", "urn:bpn:BPNL000000000CCC", "did:web:tenant-c");
        var tenantD = createContext("BPNL000000000DDD", "urn:bpn:BPNL000000000DDD", "did:web:tenant-d");
        var certC = addCertificate(tenantC.get("participantContextId").asString());
        addCertificate(tenantD.get("participantContextId").asString());

        // A document belongs to the tenant that uploaded it: certC's document is invisible to a caller
        // addressed to tenant D, retrievable only by tenant C.
        var metadataC = mapper.readTree(mvc.perform(get("/certificates/{id}", certC)
                        .header("Authorization", bearer("did:web:tenant-c")))
                .andReturn().getResponse().getContentAsString());
        var documentId = metadataC.get("documents").get(0).get("documentId").asString();
        mvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer("did:web:tenant-d")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}", documentId).header("Authorization", bearer("did:web:tenant-c")))
                .andExpect(status().isOk());

        // Acceptance is scoped too: a fulfilled exchange in tenant C cannot be accepted by a caller addressed
        // to tenant D — the exchange is unknown across the boundary (404) — but tenant C accepts it (204).
        var fulfilledForC = requestAs("did:web:tenant-c");
        var exchangeId = fulfilledForC.get("exchangeId").asString();
        var certificateId = fulfilledForC.get("certificateId").asString();
        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .header("Authorization", bearer("did:web:tenant-d"))
                        .content(acceptanceEvent(exchangeId, certificateId)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/certificate-acceptance-notifications").contentType("application/cloudevents+json")
                        .header("Authorization", bearer("did:web:tenant-c"))
                        .content(acceptanceEvent(exchangeId, certificateId)))
                .andExpect(status().isNoContent());
    }

    // --- helpers -------------------------------------------------------------------------------

    private JsonNode createContext(String bpn, String source, String did) throws Exception {
        var body = "{\"bpn\":\"" + bpn + "\",\"source\":\"" + source + "\",\"did\":\"" + did + "\"}";
        var result = mvc.perform(post("/management/v1/participant-contexts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** Uploads a document and issues a {@link #TYPE}/{@link #LOCATION} certificate under the tenant; returns its id. */
    private String addCertificate(String participantContextId) throws Exception {
        var docResult = mvc.perform(post("/management/v1/participant-contexts/" + participantContextId + "/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaType\":\"application/pdf\",\"contentBase64\":\"c2FtcGxlLXBkZg==\"}"))
                .andExpect(status().isCreated()).andReturn();
        var documentId = mapper.readTree(docResult.getResponse().getContentAsString()).get("documentId").asString();

        var body = """
                {"certificateType":"%s","registrationNumber":"REG-%s",
                 "validFrom":"2020-01-01","validUntil":"2035-01-01","trustLevel":"high",
                 "certifiedLocations":[{"bpnl":"BPNL000000TESTLE","bpna":"BPNA000000TESTAD","bpns":"%s","locationRole":"MAIN_LOCATION"}],
                 "documentIds":["%s"]}""".formatted(TYPE, participantContextId, LOCATION, documentId);
        var result = mvc.perform(post("/management/v1/participant-contexts/" + participantContextId + "/certificates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("certificateId").asString();
    }

    /** Opens a request addressed (token audience) to the tenant with the given DID. */
    private JsonNode requestAs(String tenantDid) throws Exception {
        var result = mvc.perform(post("/certificate-requests")
                        .header("Authorization", bearer(tenantDid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificateType\":\"" + TYPE + "\",\"certifiedLocations\":[\"" + LOCATION + "\"]}"))
                .andExpect(status().isAccepted()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** A bearer addressed to the tenant DID, from an arbitrary consumer counterparty. */
    private String bearer(String tenantDid) {
        return "Bearer " + siglet.mint(tenantDid, "did:web:mt-consumer", "BPNL000000000CON");
    }

    private static String searchByType() {
        return "{\"$condition\":{\"$match\":[{\"$field\":\"certificateType\",\"$eq\":\"" + TYPE + "\"}]}}";
    }

    /** A minimal {@code CertificateAcceptanceStatus} CloudEvent (ACCEPTED) for the given exchange. */
    private static String acceptanceEvent(String exchangeId, String certificateId) {
        return """
                { "specversion": "1.0", "type": "org.catena-x.ccm.CertificateAcceptanceStatus.v1",
                  "source": "urn:bpn:BPNL000000000CON", "sourcebpn": "BPNL000000000CON",
                  "id": "acc-%s", "time": "2025-05-04T08:00:00Z",
                  "data": { "exchangeId": "%s", "certificateId": "%s", "status": "ACCEPTED" } }
                """.formatted(exchangeId, exchangeId, certificateId);
    }
}
