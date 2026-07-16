package org.metaform.certo.common.persistence;

import jakarta.persistence.Converter;
import org.metaform.certo.common.model.CertificateIssuer;
import tools.jackson.core.type.TypeReference;

/** Persists a single {@link CertificateIssuer} value object as a JSON text column. */
@Converter
public class CertificateIssuerConverter extends JsonAttributeConverter<CertificateIssuer> {

    public CertificateIssuerConverter() {
        super(new TypeReference<>() {
        });
    }
}
