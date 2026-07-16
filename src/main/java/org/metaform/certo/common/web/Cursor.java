package org.metaform.certo.common.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An opaque pagination cursor: a Base64URL-encoded absolute row offset. {@link #decode} treats a
 * null/blank cursor as offset {@code 0} (the first page) and rejects a malformed one with {@code 400}.
 */
public final class Cursor {

    private Cursor() {
    }

    public static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    public static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invalid pagination cursor");
        }
    }
}
