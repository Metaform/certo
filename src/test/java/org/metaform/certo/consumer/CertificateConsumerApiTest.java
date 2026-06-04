package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CertificateConsumerApiTest {

    private static final String CE = "application/cloudevents+json";

    @Autowired
    MockMvc mvc;

    @Test
    void createdLifecycleEvent_opensExchange_andIsAccepted() throws Exception {
        mvc.perform(post("/certificate-notifications").contentType(CE)
                        .content(lifecycleCreated("exch-created-1", "cert-aaa", "2099-01-01")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/certificate-acceptance-status/{id}", "exch-created-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeId").value("exch-created-1"))
                .andExpect(jsonPath("$.certificateId").value("cert-aaa"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void createdLifecycleEvent_expiredCertificate_isRejected() throws Exception {
        mvc.perform(post("/certificate-notifications").contentType(CE)
                        .content(lifecycleCreated("exch-created-2", "cert-bbb", "2000-01-01")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/certificate-acceptance-status/{id}", "exch-created-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void fulfillmentNotification_accepted() throws Exception {
        String event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateFulfillmentStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "22222222-2222-2222-2222-222222222222",
                  "time": "2025-05-04T07:30:00Z",
                  "data": { "exchangeId": "exch-f-1", "certificateId": "cert-ccc", "status": "FULFILLED" }
                }
                """;
        mvc.perform(post("/certificate-notifications").contentType(CE).content(event))
                .andExpect(status().isNoContent());
    }

    @Test
    void batchOfEvents_accepted() throws Exception {
        String batch = "[" + lifecycleCreated("exch-batch-1", "cert-ddd", "2099-01-01")
                + "," + lifecycleWithdrawn("cert-eee") + "]";
        mvc.perform(post("/certificate-notifications").contentType(CE).content(batch))
                .andExpect(status().isNoContent());

        mvc.perform(get("/certificate-acceptance-status/{id}", "exch-batch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void acceptanceStatus_unknownExchange_notFound() throws Exception {
        mvc.perform(get("/certificate-acceptance-status/{id}", "exch-nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedEventType_badRequest() throws Exception {
        String event = """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.SomethingElse.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "33333333-3333-3333-3333-333333333333",
                  "data": { "certificateId": "cert-x", "status": "CREATED" }
                }
                """;
        mvc.perform(post("/certificate-notifications").contentType(CE).content(event))
                .andExpect(status().isBadRequest());
    }

    private static String lifecycleCreated(String exchangeId, String certificateId, String validUntil) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "subject": "BPNL0000000002CD",
                  "id": "a1b2c3d4-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
                  "time": "2025-05-04T07:00:00Z",
                  "data": {
                    "exchangeId": "%s",
                    "certificateId": "%s",
                    "version": 1,
                    "status": "CREATED",
                    "datasetId": "dataset-ccm-cert-abc123",
                    "certificateType": "ISO9001",
                    "validFrom": "2023-01-25",
                    "validUntil": "%s"
                  }
                }
                """.formatted(exchangeId, certificateId, validUntil);
    }

    private static String lifecycleWithdrawn(String certificateId) {
        return """
                {
                  "specversion": "1.0",
                  "type": "org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source": "urn:bpn:BPNL0000000001AB",
                  "id": "c3d4e5f6-7a8b-9c0d-1e2f-3a4b5c6d7e8f",
                  "data": {
                    "certificateId": "%s",
                    "version": 2,
                    "status": "WITHDRAWN",
                    "datasetId": "dataset-ccm-cert-abc123",
                    "certificateType": "ISO9001"
                  }
                }
                """.formatted(certificateId);
    }
}
