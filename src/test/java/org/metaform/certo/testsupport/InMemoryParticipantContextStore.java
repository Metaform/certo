package org.metaform.certo.testsupport;

import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny in-memory {@link ParticipantContextStore} for pure unit tests that construct their own
 * collaborators (e.g. a {@code MockSiglet}) without a Spring context or a database.
 */
public class InMemoryParticipantContextStore implements ParticipantContextStore {

    private final Map<String, ParticipantContext> contexts = new ConcurrentHashMap<>();

    @Override
    public void save(ParticipantContext context) {
        contexts.put(context.participantContextId(), context);
    }

    @Override
    public Optional<ParticipantContext> find(String participantContextId) {
        return Optional.ofNullable(contexts.get(participantContextId));
    }

    @Override
    public boolean exists(String participantContextId) {
        return participantContextId != null && contexts.containsKey(participantContextId);
    }

    @Override
    public Optional<ParticipantContext> findByDid(String did) {
        return contexts.values().stream().filter(c -> did != null && did.equals(c.did())).findFirst();
    }

    @Override
    public boolean existsByDid(String did) {
        return contexts.values().stream().anyMatch(c -> did != null && did.equals(c.did()));
    }

    @Override
    public Collection<ParticipantContext> all() {
        return List.copyOf(contexts.values());
    }
}
