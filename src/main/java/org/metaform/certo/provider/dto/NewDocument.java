package org.metaform.certo.provider.dto;

/**
 * The body of the management "add document" call — a certificate document the backend has produced,
 * uploaded ahead of the certificate that will reference it. The binary is carried Base64-encoded;
 * {@code mediaType} and {@code language} default to {@code application/pdf} / {@code en} when omitted.
 */
public record NewDocument(String mediaType, String language, String contentBase64) {
}
