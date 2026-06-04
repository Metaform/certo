package org.metaform.certo.common.cloudevent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * A CloudEvents 1.0 envelope in JSON structured mode (CX-0000 &sect;2, &sect;4), generic over its
 * {@code data} payload type.
 *
 * <p>CloudEvents attribute names are lowercase and unseparated on the wire (e.g. {@code specversion},
 * {@code datacontenttype}). The record components use idiomatic camelCase and are mapped back to the
 * wire names with {@link JsonProperty}. The {@code sourcebpn} extension attribute is REQUIRED by
 * CX-0000 &sect;2.1.2; {@code time}, {@code datacontenttype} and {@code dataschema} are RECOMMENDED.
 *
 * @param <T> the type of the {@code data} payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudEvent<T>(
        @JsonProperty("specversion") String specVersion,
        @JsonProperty("type") String type,
        @JsonProperty("source") String source,
        @JsonProperty("subject") String subject,
        @JsonProperty("id") String id,
        @JsonProperty("time") OffsetDateTime time,
        @JsonProperty("datacontenttype") String dataContentType,
        @JsonProperty("dataschema") String dataSchema,
        @JsonProperty("sourcebpn") String sourceBpn,
        @JsonProperty("data") T data) {

    public static final String SPEC_VERSION = "1.0";
    public static final String CONTENT_TYPE_JSON = "application/json";

    /** Returns a copy of this event with its {@code data} replaced (used when re-typing a decoded payload). */
    public <R> CloudEvent<R> withData(R newData) {
        return new CloudEvent<>(specVersion, type, source, subject, id, time, dataContentType, dataSchema, sourceBpn, newData);
    }
}
