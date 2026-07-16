package org.metaform.certo.provider.dto;

/**
 * The result of adding a certificate: the identity assigned to it. Issuing a certificate is a state change
 * only — waiting consumer exchanges are notified separately, per exchange, via
 * {@code POST /certificate-requests/{id}/fulfill} (each carrying that consumer's live {@code flow_id}).
 */
public record CertificateAdded(String certificateId, int revision) {
}
