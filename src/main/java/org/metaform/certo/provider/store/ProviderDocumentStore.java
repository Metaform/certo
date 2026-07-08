package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Document;

import java.util.Optional;

/**
 * Store of certificate document binaries held by the provider. The port; {@code InMemoryProviderDocumentStore}
 * is the default (in-memory) adapter, selectable via {@code certo.persistence}.
 */
public interface ProviderDocumentStore {

    void save(Document document);

    Optional<Document> find(String documentId);
}
