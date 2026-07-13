package org.metaform.certo.provider.dto;

import java.util.List;

/**
 * The result of adding a certificate: the identity assigned to it and the exchange ids that were waiting
 * for it and have now been fulfilled (and their consumers notified).
 */
public record CertificateAdded(String certificateId, int revision, List<String> fulfilledExchanges) {
}
