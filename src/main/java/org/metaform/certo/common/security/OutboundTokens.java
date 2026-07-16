package org.metaform.certo.common.security;

import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The outbound adapters' single entry point for security. For an {@link OutboundCall} it asks siglet for a
 * token scoped to the counterparty (whose DID the call carries — never resolved here), on behalf of the
 * sender's participant context. Siglet returns both the token <b>and</b> the counterparty endpoint; there is
 * no configured-URL fallback.
 */
@Component
public class OutboundTokens {

    private final SecurityTokenSource source;

    public OutboundTokens(SecurityTokenSource source) {
        this.source = source;
    }

    /** The counterparty endpoint (from siglet) and the bearer to attach. */
    public ResolvedCall forCall(OutboundCall call) {
        var token = source.resolve(call.sender().participantContextId(), call.counterpartyDid(), call.flowId());
        if (token.endpointUrl() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Siglet returned no endpoint for flow " + call.flowId());
        }
        return new ResolvedCall(token.endpointUrl(), token.bearerToken());
    }

    /** The base URL to call and the bearer to attach. */
    public record ResolvedCall(String baseUrl, String bearer) {
    }
}
