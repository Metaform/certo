package org.metaform.certo.provider.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * A certificate document held by the provider: the binary plus its descriptor (CX-0135 &sect;4). A
 * document is identified by an opaque, revision-independent {@code documentId}, belongs to the
 * {@code participantContextId} tenant that uploaded it, and may be referenced by more than one certificate
 * revision of that tenant; it is served verbatim by {@code GET /documents/{id}} with its {@code mediaType}
 * as the {@code Content-Type}.
 *
 * <p>Persisted via JPA; the binary is a {@code @Lob}. The content is defensively cloned in and out so a
 * caller's array can never mutate stored (or to-be-stored) content.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    private String documentId;
    private String participantContextId;
    private LocalDate createdDate;
    private String language;
    private String mediaType;
    @Lob
    private byte[] content;

    protected Document() {
        // for JPA
    }

    public Document(String documentId, String participantContextId, LocalDate createdDate, String language,
                    String mediaType, byte[] content) {
        this.documentId = documentId;
        this.participantContextId = participantContextId;
        this.createdDate = createdDate;
        this.language = language;
        this.mediaType = mediaType;
        this.content = content == null ? null : content.clone();
    }

    public String documentId() {
        return documentId;
    }

    public String participantContextId() {
        return participantContextId;
    }

    public LocalDate createdDate() {
        return createdDate;
    }

    public String language() {
        return language;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Returns a clone so a caller cannot mutate the stored binary through the returned reference. */
    public byte[] content() {
        return content == null ? null : content.clone();
    }
}
