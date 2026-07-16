package org.metaform.certo.common.persistence;

import jakarta.persistence.Converter;
import org.metaform.certo.provider.model.CertificateRevision;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/** Persists a {@code List<CertificateRevision>} (a certificate's revision history) as a JSON text column. */
@Converter
public class CertificateRevisionListConverter extends JsonAttributeConverter<List<CertificateRevision>> {

    public CertificateRevisionListConverter() {
        super(new TypeReference<>() {
        });
    }
}
