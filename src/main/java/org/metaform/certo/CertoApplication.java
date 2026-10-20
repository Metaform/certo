package org.metaform.certo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Certo — an implementation of the Supply Chain Certificate Exchange (SCE) data-plane
 * wire protocol.
 *
 * <p>The application hosts both the provider and consumer REST APIs in a single runtime:
 * <ul>
 *   <li>the {@code Certificate Provider API}, and</li>
 *   <li>the {@code Certificate Consumer Notification API}.</li>
 * </ul>
 *
 * <p>Storage is in-memory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CertoApplication {

    static void main(String[] args) {
        SpringApplication.run(CertoApplication.class, args);
    }
}
