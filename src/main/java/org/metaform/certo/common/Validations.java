package org.metaform.certo.common;

/**
 * Small argument/invariant guards shared across the codebase — {@code Objects.requireNonNull}-style checks
 * that throw on a missing required value and return the value so the call can be inlined at assignment.
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
