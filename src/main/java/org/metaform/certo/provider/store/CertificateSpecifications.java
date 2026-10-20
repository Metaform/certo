package org.metaform.certo.provider.store;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import org.metaform.certo.common.model.LocationFilter;
import org.metaform.certo.provider.domain.Certificate;
import org.metaform.certo.provider.dto.CertificateSearchFilter;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static org.metaform.certo.common.model.LifecycleStatus.WITHDRAWN;

/**
 * Criteria {@link Specification}s for certificate lookups that filter on the normalized
 * {@code certified_location} child rows — so coverage and search run in the database rather than by loading
 * every certificate and filtering in memory.
 *
 * <p>A {@link LocationFilter} matches a certified-location row only if the row satisfies <b>every supplied
 * property</b> (AND within the filter); a certificate matches a filter list only if every filter is matched
 * by some row (AND across filters) — one join per filter expresses the per-filter existence check.
 */
public final class CertificateSpecifications {

    private CertificateSpecifications() {
    }

    /**
     * A non-withdrawn certificate of the given tenant and type that <b>covers every requested location
     * filter</b>. An empty request matches any such certificate (coverage of the legal entity).
     */
    public static Specification<Certificate> heldCovering(String participantContextId,
                                                          String certificateType,
                                                          List<LocationFilter> requested) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.equal(root.get("certificateType"), certificateType));
            predicates.add(cb.notEqual(root.get("lifecycleStatus"), WITHDRAWN));
            for (var filter : requested) {
                predicates.add(locationMatches(root.join("certifiedLocations"), filter, cb));
            }
            if (!requested.isEmpty()) {
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * The SCE search: non-withdrawn certificates of the tenant satisfying every supplied filter property. An
     * empty filter matches all of the tenant's (non-withdrawn) certificates.
     */
    public static Specification<Certificate> matchingSearch(String participantContextId,
                                                            CertificateSearchFilter filter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.notEqual(root.get("lifecycleStatus"), WITHDRAWN));
            if (filter.certificateId() != null) {
                predicates.add(cb.equal(root.get("certificateId"), filter.certificateId()));
            }
            if (filter.certificateType() != null) {
                predicates.add(cb.equal(root.get("certificateType"), filter.certificateType()));
            }
            var locationFilters = filter.certifiedLocations();
            if (locationFilters != null && !locationFilters.isEmpty()) {
                for (var location : locationFilters) {
                    predicates.add(locationMatches(root.join("certifiedLocations"), location, cb));
                }
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** The per-filter AND over its supplied identifier properties, against one joined location row. */
    private static Predicate locationMatches(From<?, ?> location, LocationFilter filter,
                                             jakarta.persistence.criteria.CriteriaBuilder cb) {
        var terms = new ArrayList<Predicate>();
        if (filter.legalEntityId() != null) {
            terms.add(cb.equal(location.get("legalEntityId"), filter.legalEntityId()));
        }
        if (filter.siteId() != null) {
            terms.add(cb.equal(location.get("siteId"), filter.siteId()));
        }
        if (filter.addressId() != null) {
            terms.add(cb.equal(location.get("addressId"), filter.addressId()));
        }
        return cb.and(terms.toArray(Predicate[]::new));
    }
}
