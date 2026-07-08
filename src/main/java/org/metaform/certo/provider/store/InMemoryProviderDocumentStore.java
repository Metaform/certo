package org.metaform.certo.provider.store;

import org.metaform.certo.provider.model.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of certificate document binaries held by the provider (demo only), keyed by the
 * opaque, revision-independent {@code documentId}. Backs {@code GET /documents/{id}} and lets a single
 * document be shared across certificate revisions.
 */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProviderDocumentStore implements ProviderDocumentStore {

    private final ConcurrentMap<String, Document> documents = new ConcurrentHashMap<>();

    public void save(Document document) {
        documents.put(document.documentId(), document);
    }

    public Optional<Document> find(String documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }
}
