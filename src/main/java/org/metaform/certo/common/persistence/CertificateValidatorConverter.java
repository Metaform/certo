package org.metaform.certo.common.persistence;

import jakarta.persistence.Converter;
import org.metaform.certo.common.model.CertificateValidator;
import tools.jackson.core.type.TypeReference;

/** Persists a single {@link CertificateValidator} value object as a JSON text column. */
@Converter
public class CertificateValidatorConverter extends JsonAttributeConverter<CertificateValidator> {

    public CertificateValidatorConverter() {
        super(new TypeReference<>() {
        });
    }
}
