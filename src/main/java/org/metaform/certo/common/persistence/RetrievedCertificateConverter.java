package org.metaform.certo.common.persistence;

import jakarta.persistence.Converter;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import tools.jackson.core.type.TypeReference;

/**
 * Persists a {@link RetrievedCertificate} (a consumer exchange's inline pushed content: metadata plus
 * document binaries) as a JSON text column. Document {@code byte[]} content round-trips as base64.
 */
@Converter
public class RetrievedCertificateConverter extends JsonAttributeConverter<RetrievedCertificate> {

    public RetrievedCertificateConverter() {
        super(new TypeReference<>() {
        });
    }
}
