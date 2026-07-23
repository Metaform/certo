package org.metaform.certo.consumer.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The consumer's view of a certificate's lifecycle, kept in sync from {@code CertificateLifecycleStatus}
 * events (CX-0135 &sect;3.2.1) so the consumer can react to {@code MODIFIED} (a new revision is available)
 * and {@code WITHDRAWN} (no longer available). It belongs to the {@code participantContextId} tenant the
 * lifecycle event was addressed to (the verified token audience).
 *
 * <p>Identity is a surrogate id with a unique constraint on {@code (participantContextId, certificateId)} —
 * the provider-assigned {@code certificateId} is unique only <em>within</em> a tenant, so two consumer
 * tenants that learn about the same provider certificate each keep their own row (rather than colliding on a
 * shared primary key and clobbering each other's view).
 *
 * <p>Persisted via JPA with {@code @Version} optimistic locking. Because JPA does not dirty-track in-place
 * mutation of a converted collection, callers must {@code save} the aggregate after applying an update.
 */
@Entity
@Table(name = "known_certificate",
        uniqueConstraints = @UniqueConstraint(name = "uk_known_certificate_tenant_cert",
                columnNames = {"participant_context_id", "certificate_id"}))
public class KnownCertificate {

    @Id
    private String id;
    @Column(name = "certificate_id", nullable = false)
    private String certificateId;
    @Column(name = "participant_context_id", nullable = false)
    private String participantContextId;
    private Integer revision;
    @Enumerated(EnumType.STRING)
    private LifecycleStatus lifecycleStatus;
    private String certificateType;
    private LocalDate validFrom;
    private LocalDate validUntil;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "known_certificate_certified_location", joinColumns = @JoinColumn(name = "known_certificate_id"))
    private List<CertifiedLocation> certifiedLocations;
    @Version
    private long version;

    protected KnownCertificate() {
        // for JPA
    }

    public KnownCertificate(String certificateId, String participantContextId, Integer revision, LifecycleStatus lifecycleStatus,
                            String certificateType, LocalDate validFrom, LocalDate validUntil,
                            List<CertifiedLocation> certifiedLocations) {
        this.id = UUID.randomUUID().toString();
        this.certificateId = certificateId;
        this.participantContextId = participantContextId;
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
            // Mutate the collection in place rather than replacing the reference: replacing a managed
            // @ElementCollection instance makes Hibernate throw "collection no longer referenced" at flush.
            if (this.certifiedLocations == null) {
                this.certifiedLocations = new ArrayList<>(certifiedLocations);
            } else {
                this.certifiedLocations.clear();
                this.certifiedLocations.addAll(certifiedLocations);
            }
        }
    }

    public String certificateId() {
        return certificateId;
    }

    public String participantContextId() {
        return participantContextId;
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
