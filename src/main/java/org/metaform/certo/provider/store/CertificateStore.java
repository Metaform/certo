package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Certificate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of certificate artifacts held by the provider (demo only). */
@Component
public class CertificateStore {

    private final ConcurrentMap<String, Certificate> certificates = new ConcurrentHashMap<>();

    public void save(Certificate certificate) {
        certificates.put(certificate.certificateId(), certificate);
    }

    public Optional<Certificate> find(String certificateId) {
        return Optional.ofNullable(certificates.get(certificateId));
    }

    public Collection<Certificate> all() {
        return certificates.values();
    }
}
