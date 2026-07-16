package org.metaform.certo.consumer.dto;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.StatusError;

import java.util.List;

/**
 * Body of {@code POST /management/v1/consumer/exchanges/{id}/accept} — the caller's acceptance decision for
 * an exchange, reported to the provider over {@code flowId} (required when security is enabled).
 */
public record AcceptRequest(AcceptanceStatus status, List<StatusError> errors, String flowId) {
}
