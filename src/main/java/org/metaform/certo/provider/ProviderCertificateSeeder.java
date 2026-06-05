package org.metaform.certo.provider;

import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.pdf.PdfGenerator;
import org.metaform.certo.provider.model.Certificate;
import org.metaform.certo.provider.model.CertificateVersion;
import org.metaform.certo.provider.store.ProviderCertificateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a few certificates with stable identifiers at startup so the query and retrieval endpoints
 * return data on a fresh run (and so the README's curl examples are reproducible). Demo only.
 */
@Component
public class ProviderCertificateSeeder implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateSeeder.class);

    private final ProviderCertificateStore certificates;
    private final CertoProperties properties;

    public ProviderCertificateSeeder(ProviderCertificateStore certificates, CertoProperties properties) {
        this.certificates = certificates;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        // An ISO9001 certificate with two published versions (CREATED then MODIFIED).
        var iso9001 = new Certificate(
                "cert-iso9001-0001", "dataset-ccm-cert-abc123", "ISO9001",
                List.of("BPNS00000003AYRE", "BPNA000000000001"));
        iso9001.addVersion(version(iso9001, 1, LocalDate.of(2023, 1, 25), LocalDate.of(2026, 1, 24)));
        iso9001.addVersion(version(iso9001, 2, LocalDate.of(2023, 1, 25), LocalDate.of(2027, 1, 24)));
        certificates.save(iso9001);

        // An ISO14001 certificate with a single version.
        var iso14001 = new Certificate(
                "cert-iso14001-0001", "dataset-ccm-cert-xyz789", "ISO14001",
                List.of("BPNS00000003AYRE"));
        iso14001.addVersion(version(iso14001, 1, LocalDate.of(2024, 6, 1), LocalDate.of(2027, 5, 31)));
        certificates.save(iso14001);

        // An already-expired certificate, so the consumer's REJECTED path can be exercised end-to-end.
        var expired = new Certificate(
                "cert-expired-0001", "dataset-ccm-cert-expired", "IATF16949",
                List.of("BPNS00000003AYRE"));
        expired.addVersion(version(expired, 1, LocalDate.of(2018, 1, 1), LocalDate.of(2020, 1, 1)));
        certificates.save(expired);

        LOG.info("Seeded {} demo certificates", certificates.all().size());
    }

    private CertificateVersion version(Certificate cert, int version, LocalDate from, LocalDate until) {
        var pdf = PdfGenerator.generate("Company Certificate: " + cert.certificateType(), List.of(
                "Certificate ID: " + cert.certificateId(),
                "Version: " + version,
                "Type: " + cert.certificateType(),
                "Valid from: " + from,
                "Valid until: " + until,
                "Locations: " + String.join(", ", cert.locationBpns()),
                "Issued by: " + properties.provider().bpn()));
        return new CertificateVersion(version, from, until, pdf);
    }
}
