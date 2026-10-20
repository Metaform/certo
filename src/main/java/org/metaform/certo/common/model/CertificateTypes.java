package org.metaform.certo.common.model;

import java.util.regex.Pattern;

/**
 * The spelling rule for certificate type codes: an opaque code of lowercase Latin letters and digits
 * only — no whitespace, underscores, or other special characters (e.g. {@code iso9001}). The set of
 * types is open (deployments define which they support); the spelling is not. Enforced wherever a type
 * enters the system — exchange creation, search, and management ingestion — so exact-string matching
 * cannot silently miss across differently-spelled variants of the same code.
 */
public final class CertificateTypes {

    private static final Pattern CODE = Pattern.compile("[a-z0-9]+");

    private CertificateTypes() {
    }

    /** Whether the given code follows the type-code spelling rules. */
    public static boolean isValid(String certificateType) {
        return certificateType != null && CODE.matcher(certificateType).matches();
    }
}
