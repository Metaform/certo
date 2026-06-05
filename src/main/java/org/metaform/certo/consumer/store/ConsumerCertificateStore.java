package org.metaform.certo.consumer.store;

import org.metaform.certo.consumer.model.KnownCertificate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-memory store of the certificate lifecycle state the consumer has learned about (demo only). */
@Component
public class ConsumerCertificateStore {

    private final ConcurrentMap<String, KnownCertificate> certificates = new ConcurrentHashMap<>();

    public void save(KnownCertificate certificate) {
        certificates.put(certificate.certificateId(), certificate);
    }

    public Optional<KnownCertificate> find(String certificateId) {
        return Optional.ofNullable(certificates.get(certificateId));
    }
}
