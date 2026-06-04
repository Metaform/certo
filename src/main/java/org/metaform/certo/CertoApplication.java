package org.metaform.certo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Certo — a demonstration of the Catena-X CX-0135 Company Certificate Management (CCM) data-plane
 * wire protocol.
 *
 * <p>The application hosts two REST APIs in a single runtime (demo only):
 * <ul>
 *   <li>the {@code Certificate Provider API} (CX-0135 &sect;4.4), and</li>
 *   <li>the {@code Certificate Consumer Notification API} (CX-0135 &sect;4.3).</li>
 * </ul>
 *
 * <p>The Dataspace Protocol (DSP) control plane — catalog, contract negotiation and token-refresh
 * authorization — is out of scope. Storage is in-memory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CertoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CertoApplication.class, args);
    }
}
