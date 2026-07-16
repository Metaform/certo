package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.metaform.certo.MockMvcTokenConfig;
import org.metaform.certo.MockSiglet;
import org.metaform.certo.TestTenants;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Residual-2 fix: an embedded push delivers the certificate content inline (no pull endpoint to re-fetch),
 * so the consumer retains it on the exchange. A later management-driven {@code retrieve} returns that stored
 * content directly — the mechanism a security-enabled client relies on to defer acceptance. No provider is
 * running, so a successful retrieve proves the content came from storage, not a fetch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcTokenConfig.class)
class ConsumerEmbeddedRetrieveTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MockSiglet siglet;

    @Test
    void embeddedPush_contentRetainedForManagementRetrieve() throws Exception {
        // The push is addressed to the consumer tenant (aud = consumer DID), so its exchange belongs to
        // CONSUMER_PCTX — overriding the default provider-audience test token.
        var consumerBearer = "Bearer " + siglet.mint(TestTenants.CONSUMER_DID, TestTenants.PROVIDER_DID, TestTenants.PROVIDER_BPN);
        mvc.perform(post("/certificate-notifications")
                        .header("Authorization", consumerBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(embeddedEvent()))
                .andExpect(status().isNoContent());

        mvc.perform(post("/management/v1/participant-contexts/" + TestTenants.CONSUMER_PCTX + "/consumer/exchanges/{id}/retrieve", "exch-emb-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.certificateId").value("cert-emb-1"))
                .andExpect(jsonPath("$.documents[0].contentBase64").value("c2FtcGxlLXBkZg=="));
    }

    private static String embeddedEvent() {
        return """
                {
                  "specversion":"1.0","type":"org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","id":"evt-emb-1",
                  "data":{"status":"CREATED","exchangeId":"exch-emb-1","certificate":{
                    "certificateId":"cert-emb-1","revision":1,"certificateType":"ISO9001",
                    "validFrom":"2024-06-01","validUntil":"2099-05-31",
                    "documents":[{"documentId":"doc-emb-1","mediaType":"application/pdf",
                      "contentBase64":"c2FtcGxlLXBkZg=="}]}}
                }""";
    }
}
