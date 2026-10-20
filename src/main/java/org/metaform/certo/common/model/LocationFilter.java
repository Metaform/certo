package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Embeddable;

import java.util.List;

/**
 * A certified-location filter object, as carried by a certificate request's {@code certifiedLocations} and
 * the certificate search's {@code certifiedLocations} filter. A filter MAY supply any of its three
 * identifier properties and MUST supply at least one; a {@link CertifiedLocation} entry matches it only if
 * it satisfies <b>every supplied property</b> (logical AND, compared for equality).
 *
 * <p>An {@code @Embeddable}: a request's filters are persisted as child rows of an
 * {@code @ElementCollection} on the provider-side exchange so the request backlog stays queryable.
 */
@Embeddable
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationFilter(String legalEntityId, String siteId, String addressId) {

    /** Whether at least one identifier property is supplied (a filter with none is invalid). */
    public boolean specifiesAnything() {
        return legalEntityId != null || siteId != null || addressId != null;
    }

    /** AND over the supplied properties against the location's identifiers. */
    public boolean matches(CertifiedLocation location) {
        return (legalEntityId == null || legalEntityId.equals(location.legalEntityId()))
                && (siteId == null || siteId.equals(location.siteId()))
                && (addressId == null || addressId.equals(location.addressId()));
    }

    /**
     * The coverage rule: every filter is matched by at least one of the given locations (logical AND across
     * the filters). An empty or null filter list is covered — the request applies to the legal entity.
     */
    public static boolean coveredBy(List<LocationFilter> filters, List<CertifiedLocation> locations) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        var certified = locations == null ? List.<CertifiedLocation>of() : locations;
        return filters.stream().allMatch(filter -> certified.stream().anyMatch(filter::matches));
    }
}
