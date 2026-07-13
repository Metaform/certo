package org.metaform.certo.provider.dto;

/** The identity assigned to an uploaded document, to be referenced by {@code documentIds} when adding a certificate. */
public record StoredDocument(String documentId) {
}
