package org.metaform.certo.common.event.nats;

import io.nats.client.NKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the NKey path end to end against whatever BouncyCastle provider is on the classpath.
 *
 * <p>Guards a dependency trap rather than a logic bug: jnats brings BouncyCastle's <em>LTS</em>
 * provider (bcprov-lts8on), which probes for glibc-linked JNI natives during class initialisation
 * and therefore dies on a musl runtime image — which is exactly what certo ships on. The build
 * substitutes the standard provider (see build.gradle.kts); these assertions fail loudly if that
 * substitution is ever dropped or the provider stops carrying the classes jnats needs.
 *
 * <p>Note this runs on the build host, so it verifies the API contract, not the musl loading itself.
 */
class NKeyAuthHandlerTest {

    @Test
    void derivesThePublicKeyAndSignsFromASeedFile(@TempDir Path dir) throws Exception {
        var seedFile = Files.writeString(dir.resolve("nats.nk"),
                new String(NKey.createUser(null).getSeed()), StandardCharsets.UTF_8);

        var handler = new NKeyAuthHandler(seedFile);

        // A user NKey's public key is 56 chars starting with 'U' — proof the Ed25519 maths ran.
        assertThat(handler.getID()).hasSize(56);
        assertThat(new String(handler.getID())).startsWith("U");

        var nonce = "server-nonce".getBytes(StandardCharsets.UTF_8);
        assertThat(handler.sign(nonce)).hasSize(64);
        // NKey auth presents no JWT.
        assertThat(handler.getJWT()).isNull();
    }

    @Test
    void toleratesTrailingWhitespaceInTheSeedFile(@TempDir Path dir) throws Exception {
        // Vault's `kv get -field=seed > file` leaves a trailing newline; the handler trims it.
        var seed = new String(NKey.createUser(null).getSeed());
        var seedFile = Files.writeString(dir.resolve("nats.nk"), seed + "\n", StandardCharsets.UTF_8);

        assertThat(new String(new NKeyAuthHandler(seedFile).getID()))
                .isEqualTo(new String(NKey.fromSeed(seed.toCharArray()).getPublicKey()));
    }

    @Test
    void aMissingSeedFileFailsClearly(@TempDir Path dir) {
        assertThatThrownBy(() -> new NKeyAuthHandler(dir.resolve("absent.nk")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Unable to read NATS NKey seed");
    }
}
