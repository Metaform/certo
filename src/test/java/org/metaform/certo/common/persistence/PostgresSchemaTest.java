package org.metaform.certo.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the DBA-applied Postgres DDL ({@code db/postgres/schema.sql}) in sync with the JPA entities: the
 * schema script is generated from the mapping metadata with the PostgreSQL dialect and compared against
 * the checked-in file. The {@code prod} profile runs Hibernate {@code validate} and creates nothing, so
 * that script is the single source of the production schema — when an entity changes, this test fails
 * until the script is regenerated (copy the file named in the failure message over
 * {@code db/postgres/schema.sql} and have a DBA translate the diff into an ALTER for live databases).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/generated-schema/schema.sql",
        "certo.seed-sample-data=false"
})
class PostgresSchemaTest {

    static {
        // Hibernate's script export APPENDS to an existing target; start from a clean file. Runs at class
        // load, before the Spring context (and with it the export) is created.
        try {
            java.nio.file.Files.createDirectories(Path.of("build/generated-schema"));
            java.nio.file.Files.deleteIfExists(Path.of("build/generated-schema/schema.sql"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    jakarta.persistence.EntityManagerFactory entityManagerFactory; // forces JPA bootstrap (script generation)

    @Test
    void checkedInPostgresSchema_matchesTheEntities() throws Exception {
        var generated = Path.of("build/generated-schema/schema.sql");
        assertThat(generated).as("Hibernate schema export").exists();
        var expected = Path.of("db/postgres/schema.sql");
        assertThat(expected)
                .as("db/postgres/schema.sql is missing — copy %s there", generated.toAbsolutePath())
                .exists();
        assertThat(normalize(Files.readString(generated)))
                .as("db/postgres/schema.sql is out of date — regenerate by copying %s over it "
                        + "(and hand a DBA the diff as an ALTER for live databases)", generated.toAbsolutePath())
                .isEqualTo(normalize(Files.readString(expected)));
    }

    private static String normalize(String sql) {
        return sql.replace("\r\n", "\n").lines()
                .filter(line -> !line.startsWith("--"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
