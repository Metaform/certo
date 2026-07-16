package org.metaform.certo.provider;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateRevision;
import org.metaform.certo.provider.model.Document;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.metaform.certo.provider.store.ProviderDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a few sample certificates (and their document binaries, incl. generated PDFs) with stable
 * identifiers at startup so the search and retrieval endpoints return data on a fresh run. <b>Off by
 * default</b> — enable with {@code certo.seed-sample-data=true} (the test suite sets it).
 */
@Component
@ConditionalOnProperty(prefix = "certo", name = "seed-sample-data", havingValue = "true")
public class ProviderCertificateSeeder implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateSeeder.class);

    /** Stable ids + DIDs for the seeded tenants, so tests and fresh runs can address them. */
    public static final String SEED_PARTICIPANT_CONTEXT_ID = "pctx-seed-provider";
    public static final String SEED_DID = "did:web:provider";
    public static final String SEED_CONSUMER_PARTICIPANT_CONTEXT_ID = "pctx-seed-consumer";
    public static final String SEED_CONSUMER_DID = "did:web:consumer";

    /** Stable (fixed) UUID ids for the seeded certificates — certificate ids are UUIDs (also the v2.4.0 documentId). */
    public static final String SEED_ISO9001_CERTIFICATE_ID = "00000000-0000-0000-0000-000000009001";
    public static final String SEED_ISO14001_CERTIFICATE_ID = "00000000-0000-0000-0000-000000014001";
    public static final String SEED_EXPIRED_CERTIFICATE_ID = "00000000-0000-0000-0000-000000000001";

    private final ProviderCertificateStore certificates;
    private final ProviderDocumentStore documents;
    private final ParticipantContextStore contexts;
    private final CertoProperties properties;

    public ProviderCertificateSeeder(ProviderCertificateStore certificates, ProviderDocumentStore documents,
                                     ParticipantContextStore contexts, CertoProperties properties) {
        this.certificates = certificates;
        this.documents = documents;
        this.contexts = contexts;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // The seeded certificates belong to a stable provider tenant; create it (and a consumer tenant for the
        // same single runtime playing both roles) if absent.
        var pctx = SEED_PARTICIPANT_CONTEXT_ID;
        if (contexts.find(pctx).isEmpty()) {
            contexts.save(new ParticipantContext(pctx, properties.provider().bpn(),
                    properties.provider().source(), SEED_DID));
        }
        if (contexts.find(SEED_CONSUMER_PARTICIPANT_CONTEXT_ID).isEmpty()) {
            contexts.save(new ParticipantContext(SEED_CONSUMER_PARTICIPANT_CONTEXT_ID, properties.consumer().bpn(),
                    properties.consumer().source(), SEED_CONSUMER_DID));
        }

        var tuvSud = new CertificateIssuer("TÜV Süd", "BPNL0000000003EF");

        // An ISO9001 certificate with two revisions (CREATED then MODIFIED).
        var iso9001 = new Certificate(
                SEED_ISO9001_CERTIFICATE_ID, pctx, "ISO9001", "2015", "12 100 4711", "high",
                "Production and assembly of powertrain components",
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000001", "BPNS00000003AYRE", LocationRole.MAIN_LOCATION)),
                tuvSud, new CertificateValidator("TÜV Süd", "BPNL0000000003EF"));
        iso9001.addRevision(revision(iso9001, 1, LocalDate.of(2023, 1, 25), LocalDate.of(2026, 1, 24)));
        iso9001.addRevision(revision(iso9001, 2, LocalDate.of(2023, 1, 25), LocalDate.of(2027, 1, 24)));
        certificates.save(iso9001);

        // An ISO14001 certificate with a single revision.
        var iso14001 = new Certificate(
                SEED_ISO14001_CERTIFICATE_ID, pctx, "ISO14001", "2015", "14 200 0815", "high", null,
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000002", "BPNS00000003AYRE", LocationRole.MAIN_LOCATION)),
                tuvSud, null);
        iso14001.addRevision(revision(iso14001, 1, LocalDate.of(2024, 6, 1), LocalDate.of(2027, 5, 31)));
        certificates.save(iso14001);

        // An already-expired certificate, so the consumer's REJECTED path can be exercised end-to-end.
        var expired = new Certificate(
                SEED_EXPIRED_CERTIFICATE_ID, pctx, "IATF16949", "2016", "16 949 0001", "medium", null,
                List.of(new CertifiedLocation("BPNL00000001AXS", "BPNA000000000003", "BPNS00000003AYRE", LocationRole.MAIN_LOCATION)),
                tuvSud, null);
        expired.addRevision(revision(expired, 1, LocalDate.of(2018, 1, 1), LocalDate.of(2020, 1, 1)));
        certificates.save(expired);

        LOG.info("Seeded {} sample certificates for tenant {}", certificates.all().size(), pctx);
    }

    private CertificateRevision revision(Certificate cert, int revision, LocalDate from, LocalDate until) {
        var documentId = "doc-" + cert.certificateId() + "-r" + revision;
        var pdf = PdfGenerator.generate("Company Certificate: " + cert.certificateType(), List.of(
                "Certificate ID: " + cert.certificateId(),
                "Revision: " + revision,
                "Type: " + cert.certificateType(),
                "Valid from: " + from,
                "Valid until: " + until,
                "Issued by: " + properties.provider().bpn()));
        documents.save(new Document(documentId, cert.participantContextId(), from, "en", "application/pdf", pdf));
        return new CertificateRevision(revision, from, until, List.of(documentId));
    }
}
