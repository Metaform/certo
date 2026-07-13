package org.metaform.certo.protocol.ccm240.memory;

import org.metaform.certo.protocol.ccm240.Ccm240DocumentIds;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps a v3 {@code certificateId} to the v2.4.0 {@code documentId} — "the UUID of the asset under which
 * the certificate is available" (CX-0135 v2.4.0). v3 certificate ids are opaque strings (e.g.
 * {@code cert-iso9001-0001}), not UUIDs, so the adapter derives a stable UUID {@code documentId} for the
 * wire and resolves it back on inbound {@code /status}. The UUID is deterministic per certificate (so the
 * same certificate always has the same asset id) and the reverse mapping is recorded when issued.
 */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryCcm240DocumentIds implements Ccm240DocumentIds {

    private final ConcurrentMap<String, String> certificateIdByDocumentId = new ConcurrentHashMap<>();

    /** Returns the stable v2.4.0 {@code documentId} (a UUID) for a certificate, recording the reverse mapping. */
    public String documentIdFor(String certificateId) {
        var documentId = UUID.nameUUIDFromBytes(certificateId.getBytes(StandardCharsets.UTF_8)).toString();
        certificateIdByDocumentId.put(documentId, certificateId);
        return documentId;
    }

    /** Resolves the v3 {@code certificateId} for a {@code documentId} the adapter issued earlier. */
    public Optional<String> certificateIdFor(String documentId) {
        return Optional.ofNullable(documentId == null ? null : certificateIdByDocumentId.get(documentId));
    }
}
