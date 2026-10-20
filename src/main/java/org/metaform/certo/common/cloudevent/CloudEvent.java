package org.metaform.certo.common.cloudevent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * A CloudEvents 1.0 envelope in JSON structured mode, generic over its
 * {@code data} payload type.
 *
 * <p>CloudEvents attribute names are lowercase and unseparated on the wire (e.g. {@code specversion},
 * {@code datacontenttype}). The record components use idiomatic camelCase and are mapped back to the
 * wire names with {@link JsonProperty}. The {@code dataspaceidentifier} extension attribute is OPTIONAL
 * (core messaging): the producer's dataspace-assigned identifier when one exists distinct from the
 * {@code source} identity; {@code time}, {@code datacontenttype} and {@code dataschema} are RECOMMENDED.
 *
 * @param <T> the type of the {@code data} payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudEvent<T>(
        @JsonProperty("specversion") String specVersion,
        @JsonProperty("type") String type,
        @JsonProperty("source") String source,
        @JsonProperty("subject") String subject,
        @JsonProperty("id") String id,
        @JsonProperty("time") OffsetDateTime time,
        @JsonProperty("datacontenttype") String dataContentType,
        @JsonProperty("dataschema") String dataSchema,
        @JsonProperty("dataspaceidentifier") String dataspaceIdentifier,
        @JsonProperty("data") T data) {

    public static final String SPEC_VERSION = "1.0";
    public static final String CONTENT_TYPE_JSON = "application/json";
    /** The CloudEvents structured-mode media type (JSON format). */
    public static final String CONTENT_TYPE_CLOUDEVENTS = "application/cloudevents+json";

    /** Returns a copy of this event with its {@code data} replaced (used when re-typing a decoded payload). */
    public <R> CloudEvent<R> withData(R newData) {
        return new CloudEvent<>(specVersion, type, source, subject, id, time, dataContentType, dataSchema, dataspaceIdentifier, newData);
    }
}
