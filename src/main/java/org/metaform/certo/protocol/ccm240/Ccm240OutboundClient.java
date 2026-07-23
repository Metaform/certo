package org.metaform.certo.protocol.ccm240;

import okhttp3.MediaType;

/**
 * v2.4.0 outbound endpoint helpers. The actual delivery goes through the shared
 * {@link org.metaform.certo.common.OutboundJsonClient}; this holds only the v2.4.0-specific URL shape and
 * content type.
 */
public final class Ccm240OutboundClient {

    /** v2.4.0 messages are plain {@code application/json}. */
    public static final MediaType JSON = MediaType.get("application/json");

    private Ccm240OutboundClient() {
    }

    /**
     * Builds the full URL for a v2.4.0 message from the peer's <b>base</b> URL, e.g.
     * {@code base + "/companycertificate/push"}. The counterparty exposes the {@code /companycertificate/*}
     * endpoints under its base URL.
     */
    public static String endpoint(String baseUrl, String message) {
        var trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/companycertificate/" + message;
    }
}
