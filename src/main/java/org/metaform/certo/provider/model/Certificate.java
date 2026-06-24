package org.metaform.certo.provider.model;

import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A certificate artifact held by the provider (CX-0135 &sect;4). Stable across revisions under one
 * {@code certificateId}; each modification appends a new {@link CertificateRevision}. Tracks its own
 * publication lifecycle, independent of any {@code Certificate Exchange}.
 */
public class Certificate {

    private final String certificateId;
    private final String certificateType;
    private final String certificateTypeVersion;
    private final String registrationNumber;
    private final String trustLevel;
    private final String areaOfApplication;
    private final List<CertifiedLocation> certifiedLocations;
    private final CertificateIssuer issuer;
    private final CertificateValidator validator;
    private final List<CertificateRevision> revisions = new ArrayList<>();
    private LifecycleStatus lifecycleStatus;

    public Certificate(String certificateId, String certificateType, String certificateTypeVersion,
                       String registrationNumber, String trustLevel, String areaOfApplication,
                       List<CertifiedLocation> certifiedLocations,
                       CertificateIssuer issuer, CertificateValidator validator) {
        this.certificateId = certificateId;
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

    /**
     * Whether this certificate covers every requested BPN. A requested BPN is covered if any certified
     * location is identified by it (BPNL/BPNS/BPNA). An empty request applies to the legal entity and
     * is always covered.
     */
    public boolean covers(List<String> requestedBpns) {
        if (requestedBpns == null || requestedBpns.isEmpty()) {
            return true;
        }
        return requestedBpns.stream().allMatch(this::coversBpn);
    }

    /** Whether any certified location is identified by the given BPN (BPNL/BPNS/BPNA). */
    public boolean coversBpn(String bpn) {
        return certifiedLocations.stream().anyMatch(l -> l.matchesBpn(bpn));
    }

    public String certificateId() {
        return certificateId;
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
