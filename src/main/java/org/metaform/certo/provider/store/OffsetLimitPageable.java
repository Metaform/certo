package org.metaform.certo.provider.store;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} addressed by an absolute row {@code offset} + {@code limit}, rather than a page number.
 * The CX-0135 certificate search pages by an opaque offset cursor, so this maps that cursor straight onto
 * the query's {@code firstResult}/{@code maxResults} (arbitrary offsets, not just multiples of the limit).
 */
public record OffsetLimitPageable(long offset, int limit, Sort sort) implements Pageable {

    @Override
    public int getPageNumber() {
        return limit == 0 ? 0 : (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetLimitPageable(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetLimitPageable(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetLimitPageable(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimitPageable((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
