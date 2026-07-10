package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a provider publish action (demo): the provider-initiated {@code Certificate Exchange} that
 * was opened and pushed to the consumer, and whether the consumer accepted the notification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificatePublication(
        String exchangeId,
        String certificateId,
        int revision,
        boolean consumerNotified) {
}
