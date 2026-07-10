package org.metaform.certo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity configuration for the two roles this runtime plays. In a real deployment these would
 * derive from the participant's DID / BPN.
 *
 * @param provider        identity used when this runtime acts as a Certificate Provider
 * @param consumer        identity used when this runtime acts as a Certificate Consumer
 * @param providerBaseUrl base URL of the Certificate Provider data plane the consumer retrieves from
 *                        and reports acceptance to. Hardcoded; defaults
 *                        to {@code http://localhost:8080}.
 * @param consumerBaseUrl base URL of the Certificate Consumer notification API the provider pushes
 *                        lifecycle events to. Hardcoded; defaults to {@code http://localhost:8080}.
 */
@ConfigurationProperties(prefix = "certo")
public record CertoProperties(Party provider, Party consumer, String providerBaseUrl, String consumerBaseUrl) {

    /** A participant identity: the BPN and the CloudEvents {@code source} URI used when it emits events. */
    public record Party(String bpn, String source) {
    }
}
