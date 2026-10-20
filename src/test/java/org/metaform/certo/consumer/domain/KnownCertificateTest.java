package org.metaform.certo.consumer.domain;


import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code KnownCertificate.apply}: the in-place lifecycle merge, where a null field means "leave unchanged". */
class KnownCertificateTest {

    private static CertifiedLocation loc(String legalEntityId) {
        return new CertifiedLocation(legalEntityId, null, null, null);
    }

    private static KnownCertificate created() {
        return new KnownCertificate("cert-1", "pctx-c", 1, LifecycleStatus.AVAILABLE, "iso9001",
                LocalDate.parse("2024-01-01"), LocalDate.parse("2030-01-01"), List.of(loc("LE-A")));
    }

    @Test
    void apply_updatesTheLifecycleStatus() {
        var known = created();
        known.apply(2, LifecycleStatus.AVAILABLE, "iso9001", null, null, null);
        assertThat(known.lifecycleStatus()).isEqualTo(LifecycleStatus.AVAILABLE);
        assertThat(known.revision()).isEqualTo(2);
    }

    @Test
    void apply_leavesNullFieldsUnchanged() {
        var known = created();
        // A WITHDRAWN event carries id + status only; every other field is null and must not clobber existing data.
        known.apply(null, LifecycleStatus.WITHDRAWN, null, null, null, null);
        assertThat(known.lifecycleStatus()).isEqualTo(LifecycleStatus.WITHDRAWN);
        assertThat(known.revision()).isEqualTo(1);
        assertThat(known.certificateType()).isEqualTo("iso9001");
        assertThat(known.validFrom()).isEqualTo(LocalDate.parse("2024-01-01"));
        assertThat(known.validUntil()).isEqualTo(LocalDate.parse("2030-01-01"));
        assertThat(known.certifiedLocations()).extracting(CertifiedLocation::legalEntityId).containsExactly("LE-A");
    }

    @Test
    void apply_replacesCertifiedLocationsWhenProvided() {
        var known = created();
        known.apply(2, LifecycleStatus.AVAILABLE, null, null, null, List.of(loc("LE-B"), loc("LE-C")));
        assertThat(known.certifiedLocations()).extracting(CertifiedLocation::legalEntityId).containsExactly("LE-B", "LE-C");
    }

    @Test
    void apply_leavesCertifiedLocationsWhenNull() {
        var known = created();
        known.apply(2, LifecycleStatus.AVAILABLE, null, null, null, null);
        assertThat(known.certifiedLocations()).extracting(CertifiedLocation::legalEntityId).containsExactly("LE-A");
    }
}
