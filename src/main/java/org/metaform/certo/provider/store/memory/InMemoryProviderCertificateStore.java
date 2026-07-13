package org.metaform.certo.provider.store.memory;

import org.metaform.certo.provider.store.ProviderCertificateStore;

import org.metaform.certo.provider.model.Certificate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of certificate artifacts held by the provider (non-persistent). */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProviderCertificateStore implements ProviderCertificateStore {

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
