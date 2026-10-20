package org.metaform.certo.provider.store;

import jakarta.persistence.criteria.Predicate;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.provider.domain.ProviderCertificateExchange;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria {@link Specification}s for the provider's request-backlog queries, filtering on the normalized
 * {@code exchange_requested_location} child rows.
 */
public final class ExchangeSpecifications {

    private ExchangeSpecifications() {
    }

    /**
     * Exchanges of a tenant in a given fulfillment status, optionally narrowed by requested type and by
     * <b>overlapping</b> requested locations: the exchange requested a location filter naming at least one
     * of the given identifiers, in any of its identifier properties. An empty identifier list applies no
     * location constraint.
     */
    public static Specification<ProviderCertificateExchange> pendingMatching(
            String participantContextId, FulfillmentStatus status, String certificateType, List<String> anyIdentifiers) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.equal(root.get("fulfillmentStatus"), status));
            if (certificateType != null) {
                predicates.add(cb.equal(root.get("requestedType"), certificateType));
            }
            if (!anyIdentifiers.isEmpty()) {
                var location = root.join("requestedLocations");
                predicates.add(cb.or(
                        location.get("legalEntityId").in(anyIdentifiers),
                        location.get("siteId").in(anyIdentifiers),
                        location.get("addressId").in(anyIdentifiers)));
                if (query != null) {
                    query.distinct(true);
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
