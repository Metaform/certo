package org.metaform.certo.consumer.model;

import org.metaform.certo.common.model.LifecycleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * The consumer's view of a certificate's lifecycle, kept in sync from {@code CertificateLifecycleStatus}
 * events (CX-0135 &sect;4.3.1) so the consumer can react to {@code MODIFIED} (a new version is available)
 * and {@code WITHDRAWN} (no longer available).
 */
public class KnownCertificate {

    private final String certificateId;
    private int version;
    private LifecycleStatus lifecycleStatus;
    private String certificateType;
    private LocalDate validFrom;
    private LocalDate validUntil;
    private List<String> locationBpns;

    public KnownCertificate(String certificateId, int version, LifecycleStatus lifecycleStatus,
                            String certificateType, LocalDate validFrom, LocalDate validUntil,
                            List<String> locationBpns) {
        this.certificateId = certificateId;
        this.version = version;
        this.lifecycleStatus = lifecycleStatus;
        this.certificateType = certificateType;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.locationBpns = locationBpns;
    }

    /** Applies a lifecycle update; null validity/locations are left unchanged (e.g. for WITHDRAWN). */
    public void apply(int version, LifecycleStatus lifecycleStatus, String certificateType,
                      LocalDate validFrom, LocalDate validUntil, List<String> locationBpns) {
        this.version = version;
        this.lifecycleStatus = lifecycleStatus;
        if (certificateType != null) {
            this.certificateType = certificateType;
        }
        if (validFrom != null) {
            this.validFrom = validFrom;
        }
        if (validUntil != null) {
            this.validUntil = validUntil;
        }
        if (locationBpns != null) {
            this.locationBpns = locationBpns;
        }
    }

    public String certificateId() {
        return certificateId;
    }

    public int version() {
        return version;
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

    public List<String> locationBpns() {
        return locationBpns;
    }
}
