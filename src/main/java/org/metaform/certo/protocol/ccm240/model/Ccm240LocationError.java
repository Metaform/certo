package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-location error detail in a legacy v2.4.0 message: the location {@code bpn} and the errors scoped
 * to it. The v3 equivalent is a {@link org.metaform.certo.common.model.StatusError} carrying the
 * {@code bpn} as its {@code specifier}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240LocationError(String bpn, List<Ccm240Error> locationErrors) {
}
