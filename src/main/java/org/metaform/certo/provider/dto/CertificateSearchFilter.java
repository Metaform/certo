package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.LocationFilter;

import java.util.List;

/**
 * The certificate search filter object (SCE {@code POST /search}): every supplied property narrows the
 * result (logical AND), and an empty object matches all certificates visible to the caller.
 * {@code certifiedLocations} is a list of {@link LocationFilter}s, each of which must be matched by at
 * least one certified location entry (AND across the list). Unsupported filter properties are rejected by
 * the endpoint with 400.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateSearchFilter(
        String certificateId,
        String certificateType,
        List<LocationFilter> certifiedLocations) {
}
