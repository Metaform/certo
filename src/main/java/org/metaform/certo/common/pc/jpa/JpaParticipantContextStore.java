package org.metaform.certo.common.pc.jpa;

import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

/** JPA-backed {@link ParticipantContextStore}. */
@Component
public class JpaParticipantContextStore implements ParticipantContextStore {

    private final ParticipantContextRepository repository;

    public JpaParticipantContextStore(ParticipantContextRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(ParticipantContext context) {
        repository.save(context);
    }

    @Override
    public Optional<ParticipantContext> find(String participantContextId) {
        return repository.findById(participantContextId);
    }

    @Override
    public boolean exists(String participantContextId) {
        return participantContextId != null && repository.existsById(participantContextId);
    }

    @Override
    public Optional<ParticipantContext> findByDid(String did) {
        return did == null ? Optional.empty() : repository.findByDid(did);
    }

    @Override
    public boolean existsByDid(String did) {
        return did != null && repository.existsByDid(did);
    }

    @Override
    public Collection<ParticipantContext> all() {
        return repository.findAll();
    }
}
