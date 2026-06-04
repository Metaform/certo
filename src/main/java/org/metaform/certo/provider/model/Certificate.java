package org.metaform.certo.provider.model;

import org.metaform.certo.common.model.LifecycleStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A certificate artifact held by the provider (CX-0135 &sect;2.2). Stable across versions under one
 * {@code certificateId}; each modification appends a new {@link CertificateVersion}. Tracks its own
 * publication lifecycle, independent of any {@code Certificate Exchange}.
 */
public class Certificate {

    private final String certificateId;
    private final String datasetId;
    private final String certificateType;
    private final List<String> locationBpns;
    private final List<CertificateVersion> versions = new ArrayList<>();
    private LifecycleStatus lifecycleStatus;

    public Certificate(String certificateId, String datasetId, String certificateType, List<String> locationBpns) {
        this.certificateId = certificateId;
        this.datasetId = datasetId;
        this.certificateType = certificateType;
        this.locationBpns = locationBpns == null ? List.of() : List.copyOf(locationBpns);
        this.lifecycleStatus = LifecycleStatus.CREATED;
    }

    /** Publishes a new version, advancing the lifecycle to MODIFIED for any version after the first. */
    public CertificateVersion addVersion(CertificateVersion version) {
        versions.add(version);
        if (versions.size() > 1) {
            lifecycleStatus = LifecycleStatus.MODIFIED;
        }
        return version;
    }

    public void withdraw() {
        lifecycleStatus = LifecycleStatus.WITHDRAWN;
    }

    public CertificateVersion latestVersion() {
        return versions.get(versions.size() - 1);
    }

    public Optional<CertificateVersion> version(int version) {
        return versions.stream().filter(v -> v.version() == version).findFirst();
    }

    public int nextVersionNumber() {
        return versions.size() + 1;
    }

    public String certificateId() {
        return certificateId;
    }

    public String datasetId() {
        return datasetId;
    }

    public String certificateType() {
        return certificateType;
    }

    public List<String> locationBpns() {
        return locationBpns;
    }

    public LifecycleStatus lifecycleStatus() {
        return lifecycleStatus;
    }
}
