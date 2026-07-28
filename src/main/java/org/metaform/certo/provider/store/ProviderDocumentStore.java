package org.metaform.certo.provider.store;

import org.metaform.certo.provider.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store of certificate document binaries held by the provider — a Spring Data JPA repository
 * ({@code findById} / {@code save} inherited).
 */
public interface ProviderDocumentStore extends JpaRepository<Document, String> {
}
