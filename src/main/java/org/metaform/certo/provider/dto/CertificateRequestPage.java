package org.metaform.certo.provider.dto;

import java.util.List;

/** A page of pending-exchange views returned by the request queries (UC1 and UC2). */
public record CertificateRequestPage(List<PendingRequestView> items) {
}
