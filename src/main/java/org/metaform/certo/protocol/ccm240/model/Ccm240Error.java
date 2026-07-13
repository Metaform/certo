package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A single error detail entry in a v2.4.0 message ({@code Error} / {@code LocationError}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240Error(String message) {
}
