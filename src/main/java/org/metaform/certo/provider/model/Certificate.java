package org.metaform.certo.provider.model;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.common.persistence.CertificateIssuerConverter;
import org.metaform.certo.common.persistence.CertificateRevisionListConverter;
import org.metaform.certo.common.persistence.CertificateValidatorConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A certificate artifact held by the provider (CX-0135 &sect;4). Stable across revisions under one
 * {@code certificateId}; each modification appends a new {@link CertificateRevision}. Tracks its own
 * publication lifecycle, independent of any {@code Certificate Exchange}.
 *
 * <p>Persisted via JPA with {@code @Version} optimistic locking; the value-object collections and single
 * value objects are stored as JSON text columns. Concurrency safety comes from the version check on save
 * (a lost update fails), not from a JVM monitor. Because JPA does not dirty-track in-place mutation of a
 * converted collection, callers must {@code save} the aggregate after mutating it.
 */
@Entity
@Table(name = "certificate")
public class Certificate {

    @Id
    private String certificateId;
    private String participantContextId;
    private String certificateType;
    private String certificateTypeVersion;
    private String registrationNumber;
    private String trustLevel;
    private String areaOfApplication;
    // Normalized into child rows so location-based coverage/search run as DB queries (EAGER: small, and
    // always needed alongside the certificate — e.g. by toRecord outside a transaction with open-in-view off).
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "certificate_certified_location", joinColumns = @JoinColumn(name = "certificate_id"))
    private List<CertifiedLocation> certifiedLocations;
    @Convert(converter = CertificateIssuerConverter.class)
    @Column(length = 65535)
    private CertificateIssuer issuer;
    @Convert(converter = CertificateValidatorConverter.class)
    @Column(length = 65535)
    private CertificateValidator validator;
    @Convert(converter = CertificateRevisionListConverter.class)
    @Column(length = 65535)
    private List<CertificateRevision> revisions = new ArrayList<>();
    @Enumerated(EnumType.STRING)
    private LifecycleStatus lifecycleStatus;
    @Version
    private long version;

    protected Certificate() {
        // for JPA
    }

    public Certificate(String certificateId, String participantContextId, String certificateType,
                       String certificateTypeVersion, String registrationNumber, String trustLevel,
                       String areaOfApplication, List<CertifiedLocation> certifiedLocations,
                       CertificateIssuer issuer, CertificateValidator validator) {
        this.certificateId = certificateId;
        this.participantContextId = participantContextId;
        this.certificateType = certificateType;
        this.certificateTypeVersion = certificateTypeVersion;
        this.registrationNumber = registrationNumber;
        this.trustLevel = trustLevel;
        this.areaOfApplication = areaOfApplication;
        this.certifiedLocations = certifiedLocations == null ? List.of() : List.copyOf(certifiedLocations);
        this.issuer = issuer;
        this.validator = validator;
        this.lifecycleStatus = LifecycleStatus.CREATED;
    }

    /** Publishes a new revision, advancing the lifecycle to MODIFIED for any revision after the first. */
    public CertificateRevision addRevision(CertificateRevision revision) {
        revisions.add(revision);
        if (revisions.size() > 1) {
            lifecycleStatus = LifecycleStatus.MODIFIED;
        }
        return revision;
    }

    public void withdraw() {
        lifecycleStatus = LifecycleStatus.WITHDRAWN;
    }

    public CertificateRevision latestRevision() {
        return revisions.get(revisions.size() - 1);
    }

    public Optional<CertificateRevision> revision(int revision) {
        return revisions.stream().filter(r -> r.revision() == revision).findFirst();
    }

    public int nextRevisionNumber() {
        return revisions.size() + 1;
    }

    public String certificateId() {
        return certificateId;
    }

    /** The tenant (participant context) that owns/issued this certificate. */
    public String participantContextId() {
        return participantContextId;
    }

    public String certificateType() {
        return certificateType;
    }

    public String certificateTypeVersion() {
        return certificateTypeVersion;
    }

    public String registrationNumber() {
        return registrationNumber;
    }

    public String trustLevel() {
        return trustLevel;
    }

    public String areaOfApplication() {
        return areaOfApplication;
    }

    public List<CertifiedLocation> certifiedLocations() {
        return certifiedLocations;
    }

    public CertificateIssuer issuer() {
        return issuer;
    }

    public CertificateValidator validator() {
        return validator;
    }

    public LifecycleStatus lifecycleStatus() {
        return lifecycleStatus;
    }
}
