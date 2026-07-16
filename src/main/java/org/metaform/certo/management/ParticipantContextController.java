package org.metaform.certo.management;

import org.metaform.certo.common.pc.NewParticipantContext;
import org.metaform.certo.common.pc.ParticipantContext;
import org.metaform.certo.common.pc.ParticipantContextStore;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Management operations for {@link ParticipantContext}s.
 */
@RestController
@RequestMapping("/management/v1/participant-contexts")
public class ParticipantContextController {

    /** URL-safe id characters (unreserved per RFC 3986), so the id travels cleanly in siglet/management paths. */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private final ParticipantContextStore contexts;

    public ParticipantContextController(ParticipantContextStore contexts) {
        this.contexts = contexts;
    }

    /**
     * {@code POST /participant-contexts} — create a tenant. The {@code participantContextId} is caller-chosen
     * when supplied (URL-safe, unique) or a generated UUID when omitted.
     *
     * <p>The id/did uniqueness pre-checks give a friendly 409; the database's primary-key and unique-{@code
     * did} constraints are the real guard, so two concurrent creates of the same tenant cannot both commit —
     * no application-level lock (which would not hold across a cluster) is needed.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<ParticipantContext> create(@RequestBody NewParticipantContext request) {
        requirePresent("bpn", request.bpn());
        requirePresent("source", request.source());
        requirePresent("did", request.did());
        var id = resolveId(request.participantContextId());
        if (contexts.existsByDid(request.did())) {
            throw ApiException.conflict("A participant context with did " + request.did() + " already exists");
        }
        var context = new ParticipantContext(id, request.bpn(), request.source(), request.did());
        contexts.save(context);
        return ResponseEntity.status(HttpStatus.CREATED).body(context);
    }

    /** A caller-supplied id must be URL-safe and not already taken; a blank/absent one is a generated UUID. */
    private String resolveId(String requested) {
        if (requested == null || requested.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (!ID_PATTERN.matcher(requested).matches()) {
            throw ApiException.badRequest("participantContextId must match " + ID_PATTERN.pattern());
        }
        if (contexts.exists(requested)) {
            throw ApiException.conflict("A participant context with id " + requested + " already exists");
        }
        return requested;
    }

    /** {@code GET /participant-contexts/{id}} — one tenant. */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ParticipantContext get(@PathVariable("id") String participantContextId) {
        return contexts.find(participantContextId)
                .orElseThrow(() -> ApiException.notFound("Unknown participantContextId: " + participantContextId));
    }

    /** {@code GET /participant-contexts} — all tenants. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Collection<ParticipantContext> list() {
        return contexts.all();
    }

    private static void requirePresent(String field, String value) {
        ApiException.requireText(value, "A participant context must include " + field);
    }
}
