package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Store of certificate document binaries held by the provider — a Spring Data JPA repository. The
 * domain-named {@link #find} is a thin alias over {@code findById}; {@code save} is inherited.
 */
public interface ProviderDocumentStore extends JpaRepository<Document, String> {

    default Optional<Document> find(String documentId) {
        return findById(documentId);
    }
}
