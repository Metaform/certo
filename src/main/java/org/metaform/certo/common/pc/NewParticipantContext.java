package org.metaform.certo.common.pc;

/**
 * Body of {@code POST /management/v1/participant-contexts} — the identity of a new tenant. {@code bpn},
 * {@code source}, and {@code did} are required. {@code participantContextId} is <b>optional</b>: supply it to
 * choose the tenant's id (e.g. an externally-owned, idempotent key), or omit it to have the server assign a
 * generated UUID. A supplied id must be URL-safe and unique across contexts.
 *
 * @param participantContextId optional caller-chosen id; a generated UUID when null/blank
 * @param bpn                  the participant's Business Partner Number
 * @param source               the CloudEvents {@code source} URI it emits with
 * @param did                  the token audience it is addressed by (must be unique across contexts)
 */
public record NewParticipantContext(String participantContextId, String bpn, String source, String did) {
}
