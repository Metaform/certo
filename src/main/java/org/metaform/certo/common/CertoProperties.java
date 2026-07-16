package org.metaform.certo.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for the sample tenants. {@code provider}/{@code consumer} are <b>not</b> a runtime
 * identity default: real tenants are created via the management API and carry their own {@code bpn}/{@code
 * source}/{@code did} on a {@link org.metaform.certo.common.pc.ParticipantContext}. These two identities
 * are used only to seed the sample tenants (see {@code ProviderCertificateSeeder}, active only when
 * {@code certo.seed-sample-data=true}). Counterparty endpoints are never configured here — they come from the
 * siglet cache per flow.
 *
 * @param provider identity of the sample provider tenant seeded at startup
 * @param consumer identity of the sample consumer tenant seeded at startup
 */
@ConfigurationProperties(prefix = "certo")
public record CertoProperties(Party provider, Party consumer) {

    /** A participant identity: the BPN and the CloudEvents {@code source} URI used when it emits events. */
    public record Party(String bpn, String source) {
    }
}
