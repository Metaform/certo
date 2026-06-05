package org.metaform.certo.provider.api.dto;

import java.util.List;

/**
 * A page of certificate query results plus opaque cursors for related pages (CX-0135 &sect;4.4.5.1).
 * {@code next}/{@code prev} are required by the spec; {@code first}/{@code last} are optional. A null
 * cursor means there is no page in that direction (and, for first/last, that the result isn't paginated).
 */
public record CertificatePage(
        List<CertificateQueryResponse> items,
        String nextCursor,
        String prevCursor,
        String firstCursor,
        String lastCursor) {
}
