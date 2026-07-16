package org.metaform.certo.common.persistence;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base JPA {@link AttributeConverter} that stores a value object (or a list of them) as a JSON <b>text</b>
 * column. Value-object collections on the aggregates are always read and written whole with their root, so
 * a JSON blob per aggregate row (rather than child tables) keeps each aggregate a single row — the
 * {@code @Version} on the root then guards the whole aggregate, and there are no joins to manage.
 *
 * <p>Uses a Jackson 3 ({@code tools.jackson}) mapper, which
 * throws unchecked and has built-in {@code java.time} support. JSON <em>text</em> (not a DB-native json
 * type) keeps the mapping portable across H2 and Postgres; nothing queries into these columns (all
 * filtering is done in the service layer), so text is sufficient.
 *
 * @param <T> the attribute type (e.g. {@code List<StatusError>} or a single {@code CertificateIssuer})
 */
public abstract class JsonAttributeConverter<T> implements AttributeConverter<T, String> {

    protected static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final TypeReference<T> type;

    protected JsonAttributeConverter(TypeReference<T> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(T attribute) {
        return attribute == null ? null : MAPPER.writeValueAsString(attribute);
    }

    @Override
    public T convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MAPPER.readValue(dbData, type);
    }
}
