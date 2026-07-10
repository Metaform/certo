package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The reply body of a legacy v2.4.0 {@code /companycertificate/request}. Three shapes:
 * {@code IN_PROGRESS} (HTTP 202), {@code COMPLETED} with a {@code documentId} (200), or {@code REJECTED}
 * with errors (200). Built from a v3 {@code CertificateRequestResponse} via
 * {@link org.metaform.certo.protocol.ccm240.Ccm240Translation#toReplyStatus}. Matches the v2.4.0 schema
 * exactly: {@code COMPLETED} returns only the {@code documentId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Ccm240RequestReply(
        String requestStatus,
        String documentId,
        List<Ccm240Error> requestErrors,
        List<Ccm240LocationError> locationErrors) {

    public static Ccm240RequestReply inProgress() {
        return new Ccm240RequestReply("IN_PROGRESS", null, null, null);
    }

    public static Ccm240RequestReply completed(String documentId) {
        return new Ccm240RequestReply("COMPLETED", documentId, null, null);
    }

    public static Ccm240RequestReply rejected(List<Ccm240Error> requestErrors) {
        return new Ccm240RequestReply("REJECTED", null, requestErrors, null);
    }
}
