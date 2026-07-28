package org.metaform.certo.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Cursor}: opaque Base64URL offset encode/decode. */
class CursorTest {

    @Test
    void encodeThenDecode_roundTrips() {
        for (int offset : new int[]{0, 1, 50, 12345}) {
            assertThat(Cursor.decode(Cursor.encode(offset))).isEqualTo(offset);
        }
    }

    @Test
    void decode_treatsNullOrBlankAsFirstPage() {
        assertThat(Cursor.decode(null)).isZero();
        assertThat(Cursor.decode("")).isZero();
        assertThat(Cursor.decode("   ")).isZero();
    }

    @Test
    void decode_rejectsAMalformedCursor() {
        assertThatThrownBy(() -> Cursor.decode("!!!not-base64!!!"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
