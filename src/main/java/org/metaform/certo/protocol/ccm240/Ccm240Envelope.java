package org.metaform.certo.protocol.ccm240;

import org.metaform.certo.common.web.ApiException;
import org.metaform.certo.protocol.ccm240.model.Ccm240Header;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates the CX-0135 <b>v2.4.0</b> message {@code Header} against the spec schema: the required fields
 * ({@code context}, {@code messageId}, {@code senderBpn}, {@code receiverBpn}, {@code sentDateTime},
 * {@code version}), their patterns (BPNL / UUID / SemVer / date-time), and the {@code context} expected
 * for the endpoint. A malformed envelope is rejected with {@code 400} so the adapter honours the wire
 * contract rather than silently accepting anything.
 */
public final class Ccm240Envelope {

    private static final Pattern UUID =
            Pattern.compile("^(?:urn:uuid:)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern BPNL = Pattern.compile("^BPNL[a-zA-Z0-9]{12}$");
    private static final Pattern BPN_LOCATION = Pattern.compile("^BPN[AS][a-zA-Z0-9]{12}$");
    private static final Pattern VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?$");

    private Ccm240Envelope() {
    }

    // --- content-field validators (called by the controllers on the message content) --------------

    /** Requires a present, well-formed {@code BPNL…} value (e.g. {@code content.certifiedBpn}). */
    public static void requireBpnl(String field, String value) {
        requireMatch(field, value, BPNL);
    }

    /** Requires a present, well-formed UUID value (e.g. a v2.4.0 {@code documentId}). */
    public static void requireUuid(String field, String value) {
        requireMatch(field, value, UUID);
    }

    /** Validates that each location BPN (if any) is a site/address {@code BPN[AS]…}. Null list is allowed. */
    public static void validateLocationBpns(List<String> locationBpns) {
        if (locationBpns == null) {
            return;
        }
        for (var bpn : locationBpns) {
            if (bpn == null || !BPN_LOCATION.matcher(bpn).matches()) {
                throw ApiException.badRequest("Location BPN must be a site/address BPN (BPNS/BPNA): " + bpn);
            }
        }
    }

    /** Validates {@code header} for a message whose {@code context} must equal {@code expectedContext}. */
    public static void validate(Ccm240Header header, String expectedContext) {
        if (header == null) {
            throw ApiException.badRequest("Message is missing its header");
        }
        if (header.context() == null) {
            throw ApiException.badRequest("Header is missing required field 'context'");
        }
        if (!expectedContext.equals(header.context())) {
            throw ApiException.badRequest("Header context '" + header.context() + "' is not '" + expectedContext + "'");
        }
        requireMatch("messageId", header.messageId(), UUID);
        requireMatch("senderBpn", header.senderBpn(), BPNL);
        requireMatch("receiverBpn", header.receiverBpn(), BPNL);
        requireMatch("version", header.version(), VERSION);
        requireDateTime("sentDateTime", header.sentDateTime());
        if (header.relatedMessageId() != null && !UUID.matcher(header.relatedMessageId()).matches()) {
            throw ApiException.badRequest("Header field 'relatedMessageId' is not a valid UUID: " + header.relatedMessageId());
        }
    }

    private static void requireMatch(String field, String value, Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Header is missing required field '" + field + "'");
        }
        if (!pattern.matcher(value).matches()) {
            throw ApiException.badRequest("Header field '" + field + "' is malformed: " + value);
        }
    }

    private static void requireDateTime(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Header is missing required field '" + field + "'");
        }
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Header field '" + field + "' is not a valid ISO-8601 date-time: " + value);
        }
    }
}
