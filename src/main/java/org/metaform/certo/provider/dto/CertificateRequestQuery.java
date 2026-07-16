package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.FulfillmentStatus;

import java.util.List;

/**
 * Body of the request-centric query {@code POST /management/v1/certificate-requests/query} (UC1) — browse
 * or reconcile the backlog of consumer-initiated exchanges. All fields are optional filters combined with
 * AND: {@code status} (defaults to {@code CERTIFICATION_REQUESTED}, i.e. still waiting), an exact
 * {@code certificateType}, and {@code certifiedLocations} (a request matches if it targets any of them).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateRequestQuery(
        FulfillmentStatus status,
        String certificateType,
        List<String> certifiedLocations) {
}
