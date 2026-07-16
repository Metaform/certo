package org.metaform.certo.common.pc.jpa;

import org.metaform.certo.common.pc.ParticipantContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for {@link ParticipantContext} tenants. */
public interface ParticipantContextRepository extends JpaRepository<ParticipantContext, String> {

    /** Resolves the context addressed by a token audience ({@code did}) — the inbound tenant lookup. */
    Optional<ParticipantContext> findByDid(String did);

    boolean existsByDid(String did);
}
