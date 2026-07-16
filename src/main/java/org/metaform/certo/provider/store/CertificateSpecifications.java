package org.metaform.certo.provider.store;

import jakarta.persistence.criteria.Predicate;
import org.metaform.certo.provider.dto.CertificateQuery;
import org.metaform.certo.provider.model.Certificate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static org.metaform.certo.common.model.LifecycleStatus.WITHDRAWN;

/**
 * Criteria {@link Specification}s for certificate lookups that filter on the normalized
 * {@code certified_location} child rows — so coverage and search run in the database rather than by loading
 * every certificate and filtering in memory.
 */
public final class CertificateSpecifications {

    private CertificateSpecifications() {
    }

    /**
     * A non-withdrawn certificate of the given tenant and type that <b>covers every requested BPN</b> — each
     * requested BPN must match some certified location's BPNL/BPNS/BPNA anchor. An empty request matches any
     * such certificate (coverage of the legal entity). One join per requested BPN ANDs the existence checks.
     */
    public static Specification<Certificate> heldCovering(String participantContextId,
                                                          String certificateType,
                                                          List<String> requestedBpns) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.equal(root.get("certificateType"), certificateType));
            predicates.add(cb.notEqual(root.get("lifecycleStatus"), WITHDRAWN));
            for (var bpn : requestedBpns) {
                var location = root.join("certifiedLocations");
                predicates.add(cb.or(
                        cb.equal(location.get("bpnl"), bpn),
                        cb.equal(location.get("bpna"), bpn),
                        cb.equal(location.get("bpns"), bpn)));
            }
            if (!requestedBpns.isEmpty()) {
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * The CX-0135 &sect;3.3.4 search: non-withdrawn certificates of the tenant satisfying every {@code $eq}
     * clause. {@code certificateType} is a scalar equality; {@code certifiedLocations.bpnl/bpns/bpna} each
     * become an independent location join (a certificate matches if <em>some</em> location satisfies the
     * clause — clauses may be satisfied by different locations). Fields are validated by the caller.
     */
    public static Specification<Certificate> matchingSearch(String participantContextId,
                                                            List<CertificateQuery.MatchClause> clauses) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("participantContextId"), participantContextId));
            predicates.add(cb.notEqual(root.get("lifecycleStatus"), WITHDRAWN));
            var joined = false;
            for (var clause : clauses) {
                switch (clause.field()) {
                    case "certificateType" -> predicates.add(cb.equal(root.get("certificateType"), clause.eq()));
                    case "certifiedLocations.bpnl" -> {
                        predicates.add(cb.equal(root.join("certifiedLocations").get("bpnl"), clause.eq()));
                        joined = true;
                    }
                    case "certifiedLocations.bpns" -> {
                        predicates.add(cb.equal(root.join("certifiedLocations").get("bpns"), clause.eq()));
                        joined = true;
                    }
                    case "certifiedLocations.bpna" -> {
                        predicates.add(cb.equal(root.join("certifiedLocations").get("bpna"), clause.eq()));
                        joined = true;
                    }
                    default -> throw new IllegalArgumentException("Unsupported search field: " + clause.field());
                }
            }
            if (joined) {
                query.distinct(true);
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
