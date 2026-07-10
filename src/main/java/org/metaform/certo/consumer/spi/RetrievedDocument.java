package org.metaform.certo.consumer.spi;

/**
 * A document binary the consumer pulled from the provider via {@code GET /documents/{id}}
 * (CX-0135 &sect;3.3.3), with its id and media type.
 */
public record RetrievedDocument(String documentId, String mediaType, byte[] content) {
}
