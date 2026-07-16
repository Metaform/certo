package org.metaform.certo;

import org.metaform.certo.provider.ProviderCertificateSeeder;

/**
 * Shared identities for the test suite's seeded tenants. The {@link ProviderCertificateSeeder} (enabled for
 * all tests via {@code certo.seed-sample-data=true}) registers a provider participant context (owning the
 * seeded certificates) and a consumer participant context for the same single runtime playing both roles.
 * Tests address them by these stable ids/DIDs and mint correctly-audienced tokens against them.
 */
public final class TestTenants {

    private TestTenants() {
    }

    public static final String PROVIDER_PCTX = ProviderCertificateSeeder.SEED_PARTICIPANT_CONTEXT_ID;
    public static final String PROVIDER_DID = ProviderCertificateSeeder.SEED_DID;
    public static final String PROVIDER_BPN = "BPNL0000000001AB";
    public static final String PROVIDER_SOURCE = "urn:bpn:BPNL0000000001AB";

    public static final String CONSUMER_PCTX = ProviderCertificateSeeder.SEED_CONSUMER_PARTICIPANT_CONTEXT_ID;
    public static final String CONSUMER_DID = ProviderCertificateSeeder.SEED_CONSUMER_DID;
    public static final String CONSUMER_BPN = "BPNL0000000002CD";
    public static final String CONSUMER_SOURCE = "urn:bpn:BPNL0000000002CD";

    // Seeded certificate ids (UUIDs — also the v2.4.0 documentId).
    public static final String ISO9001_CERT_ID = ProviderCertificateSeeder.SEED_ISO9001_CERTIFICATE_ID;
    public static final String ISO14001_CERT_ID = ProviderCertificateSeeder.SEED_ISO14001_CERTIFICATE_ID;
    public static final String EXPIRED_CERT_ID = ProviderCertificateSeeder.SEED_EXPIRED_CERTIFICATE_ID;
}
