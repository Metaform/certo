package org.metaform.certo.provider.dto;

import org.metaform.certo.common.model.CertificateRecord;

import java.util.List;

/**
 * One page of certificate search results plus the opaque cursor of the next page; a null cursor means the
 * page is the last (SCE search pagination).
 */
public record CertificatePage(List<CertificateRecord> items, String nextCursor) {
}
