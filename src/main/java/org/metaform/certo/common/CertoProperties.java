package org.metaform.certo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity configuration for the two roles this runtime plays. In a real deployment these would
 * derive from the participant's DID / BPN established during DSP negotiation (out of scope here).
 */
@ConfigurationProperties(prefix = "certo")
public record CertoProperties(Party provider, Party consumer) {

    /** A participant identity: the BPN and the CloudEvents {@code source} URI used when it emits events. */
    public record Party(String bpn, String source) {
    }
}
