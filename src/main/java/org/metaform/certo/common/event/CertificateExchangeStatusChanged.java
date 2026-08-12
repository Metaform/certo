package org.metaform.certo.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.StatusError;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A status change on one phase of a {@code Certificate Exchange} — the {@code data} payload of the
 * CloudEvent published to NATS.
 *
 * <p>Recorded by the exchange aggregates themselves (see {@code ProviderCertificateExchange} and
 * {@code ConsumerCertificateExchange}) and drained by Spring Data on {@code save()}, so it is a
 * statement of fact about committed state rather than an intent.
 *
 * @param role                 which aggregate observed the change; a single Certo may hold both sides
 * @param phase                Fulfillment or Acceptance (CX-0135 &sect;2.1.3)
 * @param eventType            the catalogue entry — carries the subject and CloudEvents type
 * @param exchangeId           the exchange this change belongs to
 * @param participantContextId the tenant that owns the exchange; resolves the CloudEvents source/sourcebpn
 * @param counterpartyBpn      the other party's BPN (the consumer's, seen from the provider, and vice versa)
 * @param counterpartyDid      the other party's DID
 * @param certificateId        null while the certificate identity is still unknown (a pending request)
 * @param revision             the certificate revision, null alongside an unknown {@code certificateId}
 * @param previousStatus       the status being left, or <b>null when the exchange was just opened</b> —
 *                             which is what distinguishes "opened in state X" from "transitioned to X"
 * @param status               the status now in effect
 * @param errors               CX-0135 &sect;4.4.4 error details accompanying the new status, if any
 * @param occurredAt           when the change was recorded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateExchangeStatusChanged(
        ExchangeRole role,
        ExchangePhase phase,
        ExchangeEventType eventType,
        String exchangeId,
        String participantContextId,
        String counterpartyBpn,
        String counterpartyDid,
        String certificateId,
        Integer revision,
        String previousStatus,
        String status,
        List<StatusError> errors,
        OffsetDateTime occurredAt) {

    public CertificateExchangeStatusChanged {
        errors = errors == null ? null : List.copyOf(errors);
    }
}
