package org.metaform.certo.common.web;

/**
 * The {@code Error} schema shared by both APIs — a single human-readable {@code message}.
 */
public record ErrorResponse(String message) {
}
