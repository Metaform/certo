package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An error detail entry carried in status payloads (CX-0135 &sect;4.4.4).
 *
 * @param message   a human-readable description of the error (mandatory)
 * @param specifier an optional identifier scoping the error to a particular element of the
 *                  certificate, such as a site BPN; if omitted, the error applies to the
 *                  certificate as a whole
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusError(String message, String specifier) {

    public StatusError(String message) {
        this(message, null);
    }
}
