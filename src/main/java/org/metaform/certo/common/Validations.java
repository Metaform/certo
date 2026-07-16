package org.metaform.certo.common;

/**
 * Validations.
 */
public final class Validations {

    private Validations() {
    }

    /**
     * Ensures a required string is present — non-null and not blank — returning it so the call can be inlined
     * into an assignment. Throws {@link NullPointerException} (like {@code Objects.requireNonNull}), naming
     * the offending {@code field} in the message.
     */
    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new NullPointerException(field + " must not be null or empty");
        }
        return value;
    }
}
