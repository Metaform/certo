package org.metaform.certo.common.web;

import org.springframework.http.HttpStatus;

/**
 * A business/protocol error that maps to a specific HTTP status and the {@link ErrorResponse} body.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Ensures a required text value from client input is present (non-null, not blank), returning it so the
     * call can be inlined; otherwise rejects the request with {@code 400 Bad Request} and the given message.
     * The client-input counterpart to {@link org.metaform.certo.common.Validations#requireNonBlank} (which
     * signals an internal precondition with a 500).
     */
    public static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw badRequest(message);
        }
        return value;
    }

    /** A missing, malformed, or unverifiable security token on a protocol call. */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    /** An illegal state-machine transition or a violated precondition (e.g. terminal immutability). */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
