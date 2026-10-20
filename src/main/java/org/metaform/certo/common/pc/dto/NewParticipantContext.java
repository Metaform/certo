package org.metaform.certo.common.pc.dto;

/**
 * Body of {@code POST /management/v1/participant-contexts} — the identity of a new tenant. {@code did} is
 * required; {@code dataspaceIdentifier} is optional (most dataspaces assign one, some do not).
 * {@code participantContextId} is <b>optional</b>: supply it to choose the tenant's id (e.g. an
 * externally-owned, idempotent key), or omit it to have the server assign a generated UUID. A supplied id
 * must be URL-safe and unique across contexts.
 *
 * @param participantContextId optional caller-chosen id; a generated UUID when null/blank
 * @param dataspaceIdentifier  the participant's dataspace-assigned identifier, when one exists
 * @param did                  the participant identity (token audience and event source; unique across contexts)
 */
public record NewParticipantContext(String participantContextId, String dataspaceIdentifier, String did) {
}
