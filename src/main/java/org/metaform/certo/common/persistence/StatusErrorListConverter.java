package org.metaform.certo.common.persistence;

import jakarta.persistence.Converter;
import org.metaform.certo.common.model.StatusError;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/** Persists a {@code List<StatusError>} (exchange fulfillment/acceptance errors) as a JSON text column. */
@Converter
public class StatusErrorListConverter extends JsonAttributeConverter<List<StatusError>> {

    public StatusErrorListConverter() {
        super(new TypeReference<>() {
        });
    }
}
