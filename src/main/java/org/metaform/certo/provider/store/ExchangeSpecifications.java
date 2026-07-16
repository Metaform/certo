package org.metaform.certo.provider.store;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.provider.model.ProviderCertificateExchange;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Criteria {@link Specification}s for the provider's request-backlog queries, filtering on the normalized
 * {@code exchange_requested_location} child rows so location overlap/subset run in the database.
 */
public final class ExchangeSpecifications {

    private ExchangeSpecifications() {
    }

    /**
     * Consumer-initiated exchanges of a tenant in a given fulfillment status, optionally narrowed by requested
     * type and by <b>overlapping</b> requested locations (the exchange requested at least one of the filter
     * BPNs). An empty location filter applies no location constraint.
     */
    public static Specification<ProviderCertificateExchange> pendingMatching(
            String participantContextId, FulfillmentStatus status, String certificateType, List<String> anyLocations) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.isTrue(root.get("consumerInitiated")));
            predicates.add(cb.equal(root.get("fulfillmentStatus"), status));
            if (certificateType != null) {
                predicates.add(cb.equal(root.get("requestedType"), certificateType));
            }
            if (!anyLocations.isEmpty()) {
                predicates.add(root.join("requestedLocations").in(anyLocations));
                if (query != null) {
                    query.distinct(true);
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Consumer-initiated exchanges of a tenant awaiting fulfillment for the given type whose requested
     * locations are <b>all covered</b> by {@code coveredBpns} (the issued certificate's location anchors) —
     * expressed as "there is no requested location outside the covered set". An exchange that requested no
     * location is covered.
     */
    public static Specification<ProviderCertificateExchange> fulfillableBy(
            String participantContextId, String certificateType, Set<String> coveredBpns) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.isTrue(root.get("consumerInitiated")));
            predicates.add(cb.equal(root.get("fulfillmentStatus"), FulfillmentStatus.CERTIFICATION_REQUESTED));
            predicates.add(cb.equal(root.get("requestedType"), certificateType));
            var sub = query.subquery(String.class);
            var subRoot = sub.from(ProviderCertificateExchange.class);
            Join<ProviderCertificateExchange, String> requested = subRoot.join("requestedLocations");
            Predicate outsideCovered = coveredBpns.isEmpty() ? cb.conjunction() : cb.not(requested.in(coveredBpns));
            sub.select(requested).where(cb.equal(subRoot, root), outsideCovered);
            predicates.add(cb.not(cb.exists(sub)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
