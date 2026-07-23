package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Store of certificate artifacts held by the provider — a Spring Data JPA repository ({@code findById} /
 * {@code findAll} / {@code save} inherited). {@link JpaSpecificationExecutor} backs the coverage/search
 * queries (see {@code CertificateSpecifications}).
 */
public interface ProviderCertificateStore
        extends JpaRepository<Certificate, String>, JpaSpecificationExecutor<Certificate> {
}
