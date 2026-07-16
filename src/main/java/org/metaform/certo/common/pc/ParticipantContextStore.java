package org.metaform.certo.common.pc;

import java.util.Collection;
import java.util.Optional;

/**
 * Store of {@link ParticipantContext}s. The port; the JPA adapter is the sole implementation.
 */
public interface ParticipantContextStore {

    void save(ParticipantContext context);

    Optional<ParticipantContext> find(String participantContextId);

    /** Whether a context with this id exists — an existence check that does not load the row. */
    boolean exists(String participantContextId);

    /** Resolves the context addressed by a token audience ({@code did}) — the inbound tenant lookup. */
    Optional<ParticipantContext> findByDid(String did);

    /** Whether a context with this audience {@code did} exists — an existence check that does not load the row. */
    boolean existsByDid(String did);

    Collection<ParticipantContext> all();
}
