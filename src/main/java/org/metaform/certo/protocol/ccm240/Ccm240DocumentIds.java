package org.metaform.certo.protocol.ccm240;

import java.util.Optional;

/**
 * Maps a v3 {@code certificateId} to the v2.4.0 {@code documentId} (a UUID asset id) and back. The port;
 * {@code InMemoryCcm240DocumentIds} is the default (in-memory) adapter, selectable via
 * {@code certo.persistence}.
 */
public interface Ccm240DocumentIds {

    /** Returns the stable v2.4.0 {@code documentId} (a UUID) for a certificate, recording the reverse mapping. */
    String documentIdFor(String certificateId);

    /** Resolves the v3 {@code certificateId} for a {@code documentId} the adapter issued earlier. */
    Optional<String> certificateIdFor(String documentId);
}
