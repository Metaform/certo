package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * A self-contained CCM certificate query (CX-0135 &sect;3.3.4), the body of
 * {@code POST /certificates/search}. A single root {@code $condition} whose {@code $match} array
 * combines its clauses with logical AND.
 */
public record CertificateQuery(@NotNull @JsonProperty("$condition") Condition condition) {

    /** A conjunction of match clauses. A certificate matches only if it satisfies every clause. */
    public record Condition(@NotNull @JsonProperty("$match") List<MatchClause> match) {
    }

    /** An equality comparison of a single dotted field path against a string value. */
    public record MatchClause(
            @JsonProperty("$field") String field,
            @JsonProperty("$eq") String eq) {
    }
}
