package org.metaform.certo.consumer;

import org.metaform.certo.testsupport.MockMvcTokenConfig;

import org.junit.jupiter.api.Test;
import org.metaform.certo.consumer.spi.InboundNotification;
import org.metaform.certo.consumer.spi.InboundNotificationListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inbound extension point: an inbound notification is recorded and emitted to every registered
 * {@link InboundNotificationListener}, so a plugged-in client can drive the management API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcTokenConfig.class)
class ConsumerNotificationListenerTest {

    @TestConfiguration
    static class Config {
        @Bean
        CapturingListener capturingListener() {
            return new CapturingListener();
        }
    }

    static class CapturingListener implements InboundNotificationListener {
        final List<InboundNotification> events = new CopyOnWriteArrayList<>();

        @Override
        public void onNotification(InboundNotification event) {
            events.add(event);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    org.metaform.certo.testsupport.MockSiglet siglet;

    @Autowired
    CapturingListener listener;

    @Test
    void lifecycleNotification_emitsEventToListener() throws Exception {
        listener.events.clear();
        // A provider->consumer push: addressed to the consumer tenant, sent by the provider — the event
        // source must match that verified sender.
        mvc.perform(post("/notifications")
                        .header("Authorization", "Bearer " + siglet.mint(
                                org.metaform.certo.testsupport.TestTenants.CONSUMER_DID,
                                org.metaform.certo.testsupport.TestTenants.PROVIDER_DID,
                                org.metaform.certo.testsupport.TestTenants.PROVIDER_DATASPACE_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleEvent()))
                .andExpect(status().isNoContent());

        assertThat(listener.events).hasSize(1);
        var event = listener.events.get(0);
        assertThat(event.kind()).isEqualTo(InboundNotification.Kind.LIFECYCLE);
        assertThat(event.status()).isEqualTo("CREATED");
        // A lifecycle event is informational and correlates to no exchange.
        assertThat(event.exchangeId()).isNull();
        assertThat(event.certificateId()).isEqualTo("cert-listen-1");
    }

    private static String lifecycleEvent() {
        return """
                {
                  "specversion":"1.0","type":"org.dsaf.sce.CertificateLifecycleStatus.v1",
                  "source":"did:web:provider","id":"evt-listen-1",
                  "data":{"status":"CREATED","certificate":{
                    "certificateId":"cert-listen-1","revision":1,"certificateType":"iso9001",
                    "validFrom":"2024-06-01","validUntil":"2028-05-31"}}
                }""";
    }
}
