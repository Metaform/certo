package org.metaform.certo.provider.model;

import java.time.LocalDate;

/**
 * A certificate document held by the provider: the binary plus its descriptor (CX-0135 &sect;4). A
 * document is identified by an opaque, revision-independent {@code documentId} and may be referenced by
 * more than one certificate revision; it is served verbatim by {@code GET /documents/{id}} with its
 * {@code mediaType} as the {@code Content-Type}.
 */
public record Document(
        String documentId,
        LocalDate createdDate,
        String language,
        String mediaType,
        byte[] content) {
}
