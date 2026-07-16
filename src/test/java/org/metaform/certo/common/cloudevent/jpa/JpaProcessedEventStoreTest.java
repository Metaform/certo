package org.metaform.certo.common.cloudevent.jpa;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.cloudevent.ProcessedEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JPA idempotency store's {@code claim} contract against a real (embedded H2) database: the
 * first claim of a key wins and a duplicate is denied. The transactional rollback-on-failure behaviour is
 * exercised end-to-end by the notification flow tests.
 */
@SpringBootTest
class JpaProcessedEventStoreTest {

    @Autowired
    ProcessedEventStore store;

    @Test
    void claim_isWonOnceThenDeniedForDuplicates() {
        assertThat(store.claim("evt-dedup-1")).isTrue();   // first delivery wins
        assertThat(store.claim("evt-dedup-1")).isFalse();  // duplicate delivery is skipped
        assertThat(store.claim("evt-dedup-1")).isFalse();

        assertThat(store.claim("evt-dedup-2")).isTrue();   // a distinct key is independent
    }
}
