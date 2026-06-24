package org.metaform.certo.consumer.model;

import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * The consumer's view of a certificate's lifecycle, kept in sync from {@code CertificateLifecycleStatus}
 * events (CX-0135 &sect;3.2.1) so the consumer can react to {@code MODIFIED} (a new revision is available)
 * and {@code WITHDRAWN} (no longer available).
 */
public class KnownCertificate {

    private final String certificateId;
    private Integer revision;
    private LifecycleStatus lifecycleStatus;
    private String certificateType;
    private LocalDate validFrom;
    private LocalDate validUntil;
    private List<CertifiedLocation> certifiedLocations;

    public KnownCertificate(String certificateId, Integer revision, LifecycleStatus lifecycleStatus,
                            String certificateType, LocalDate validFrom, LocalDate validUntil,
                            List<CertifiedLocation> certifiedLocations) {
        this.certificateId = certificateId;
        this.revision = revision;
        this.lifecycleStatus = lifecycleStatus;
        this.certificateType = certificateType;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.certifiedLocations = certifiedLocations;
    }

    /** Applies a lifecycle update; null fields are left unchanged (e.g. WITHDRAWN carries id only). */
    public void apply(Integer revision, LifecycleStatus lifecycleStatus, String certificateType,
                      LocalDate validFrom, LocalDate validUntil, List<CertifiedLocation> certifiedLocations) {
        this.lifecycleStatus = lifecycleStatus;
        if (revision != null) {
            this.revision = revision;
        }
        if (certificateType != null) {
            this.certificateType = certificateType;
        }
        if (validFrom != null) {
            this.validFrom = validFrom;
        }
        if (validUntil != null) {
            this.validUntil = validUntil;
        }
        if (certifiedLocations != null) {
            this.certifiedLocations = certifiedLocations;
        }
    }

    public String certificateId() {
        return certificateId;
    }

    public Integer revision() {
        return revision;
    }

    public LifecycleStatus lifecycleStatus() {
        return lifecycleStatus;
    }

    public String certificateType() {
        return certificateType;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public LocalDate validUntil() {
        return validUntil;
    }

    public List<CertifiedLocation> certifiedLocations() {
        return certifiedLocations;
    }
}
