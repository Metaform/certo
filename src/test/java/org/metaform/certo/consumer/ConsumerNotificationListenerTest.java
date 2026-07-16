package org.metaform.certo.consumer;

import org.junit.jupiter.api.Test;
import org.metaform.certo.consumer.spi.InboundCcmEvent;
import org.metaform.certo.consumer.spi.InboundNotificationListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.metaform.certo.MockMvcTokenConfig;
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
 * The inbound extension point: an inbound CCM notification is recorded and emitted to every registered
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
        final List<InboundCcmEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onNotification(InboundCcmEvent event) {
            events.add(event);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CapturingListener listener;

    @Test
    void lifecycleNotification_emitsEventToListener() throws Exception {
        listener.events.clear();
        mvc.perform(post("/certificate-notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleEvent()))
                .andExpect(status().isNoContent());

        assertThat(listener.events).hasSize(1);
        var event = listener.events.get(0);
        assertThat(event.kind()).isEqualTo(InboundCcmEvent.Kind.LIFECYCLE);
        assertThat(event.status()).isEqualTo("CREATED");
        assertThat(event.exchangeId()).isEqualTo("exch-listen-1");
        assertThat(event.certificateId()).isEqualTo("cert-listen-1");
    }

    private static String lifecycleEvent() {
        return """
                {
                  "specversion":"1.0","type":"org.catena-x.ccm.CertificateLifecycleStatus.v1",
                  "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","id":"evt-listen-1",
                  "data":{"status":"CREATED","exchangeId":"exch-listen-1","certificate":{
                    "certificateId":"cert-listen-1","revision":1,"certificateType":"ISO9001",
                    "validFrom":"2024-06-01","validUntil":"2028-05-31"}}
                }""";
    }
}
