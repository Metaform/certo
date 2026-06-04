package org.metaform.certo.provider.api.dto;

import java.util.List;

/**
 * A page of certificate query results plus opaque cursors for adjacent pages (CX-0135 &sect;4.4.5.1).
 * A null cursor means there is no page in that direction.
 */
public record CertificatePage(
        List<CertificateQueryResponse> items,
        String nextCursor,
        String prevCursor) {
}
