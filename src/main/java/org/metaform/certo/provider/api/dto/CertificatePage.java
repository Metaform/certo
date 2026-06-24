package org.metaform.certo.provider.api.dto;

import org.metaform.certo.common.model.CertificateRecord;

import java.util.List;

/**
 * One page of {@code POST /certificates/search} results plus opaque cursors for the adjacent pages
 * (CX-0135 &sect;3.3.4). Pagination is conveyed to the client via the RFC 8288 {@code Link} header
 * ({@code next}/{@code prev}); a null cursor means there is no page in that direction.
 */
public record CertificatePage(List<CertificateRecord> items, String nextCursor, String prevCursor) {
}
