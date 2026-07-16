package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.Optional;

/**
 * Store of certificate artifacts held by the provider — a Spring Data JPA repository. The domain-named
 * {@link #find} / {@link #all} are thin aliases over {@code findById} / {@code findAll}; {@code save} is
 * inherited. {@link JpaSpecificationExecutor} backs the coverage/search queries (see
 * {@code CertificateSpecifications}).
 */
public interface ProviderCertificateStore extends JpaRepository<Certificate, String>,
        JpaSpecificationExecutor<Certificate> {

    default Optional<Certificate> find(String certificateId) {
        return findById(certificateId);
    }

    default Collection<Certificate> all() {
        return findAll();
    }
}
