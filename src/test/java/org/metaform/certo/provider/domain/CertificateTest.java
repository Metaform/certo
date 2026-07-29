package org.metaform.certo.provider.domain;



import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.LifecycleStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Certificate revisioning + lifecycle (CX-0135 §2.2.4). */
class CertificateTest {

    private static Certificate certificate() {
        return new Certificate("cert-1", "pctx-p", "ISO9001", "2015", "REG-1",
                "high", "Production", List.of(), null, null);
    }

    private static CertificateRevision revision(int n) {
        return new CertificateRevision(n, LocalDate.parse("2024-01-01"), LocalDate.parse("2030-01-01"),
                List.of("doc-" + n));
    }

    @Test
    void firstRevision_keepsLifecycleCreated() {
        var certificate = certificate();
        certificate.addRevision(revision(1));
        assertThat(certificate.lifecycleStatus()).isEqualTo(LifecycleStatus.CREATED);
        assertThat(certificate.latestRevision().revision()).isEqualTo(1);
        assertThat(certificate.nextRevisionNumber()).isEqualTo(2);
    }

    @Test
    void secondRevision_advancesLifecycleToModified() {
        var certificate = certificate();
        certificate.addRevision(revision(1));
        certificate.addRevision(revision(2));
        assertThat(certificate.lifecycleStatus()).isEqualTo(LifecycleStatus.MODIFIED);
        assertThat(certificate.latestRevision().revision()).isEqualTo(2);
        assertThat(certificate.nextRevisionNumber()).isEqualTo(3);
    }

    @Test
    void revisionByNumber_resolvesOnlyExistingRevisions() {
        var certificate = certificate();
        certificate.addRevision(revision(1));
        certificate.addRevision(revision(2));
        assertThat(certificate.revision(1)).isPresent();
        assertThat(certificate.revision(2)).isPresent();
        assertThat(certificate.revision(99)).isEmpty();
    }

    @Test
    void withdraw_setsLifecycleWithdrawn() {
        var certificate = certificate();
        certificate.addRevision(revision(1));
        certificate.withdraw();
        assertThat(certificate.lifecycleStatus()).isEqualTo(LifecycleStatus.WITHDRAWN);
    }
}
