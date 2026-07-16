package org.metaform.certo.common.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import okhttp3.Request;
import org.metaform.certo.common.RetryingHttpClient;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Resolves an outbound token + endpoint from this runtime's siglet cache:
 * {@code GET /tokens/{participant_context_id}/{flow_id}} returns {@code { token, endpoint }} — the bearer
 * JWT (minted by the counterparty's siglet, already scoped to the counterparty) and the counterparty URL to
 * call. Siglet is the only token backend.
 */
@Component
public class SigletTokenSource implements SecurityTokenSource {

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public SigletTokenSource(RetryingHttpClient http, ObjectMapper mapper, SecurityProperties properties) {
        this.http = http;
        this.mapper = mapper;
        var base = properties.sigletBaseUrl();
        this.baseUrl = (base != null && base.endsWith("/")) ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public ResolvedToken resolve(String participantContextId, String counterpartyDid, String flowId) {
        ApiException.requireText(participantContextId, "A secured outbound call requires a participantContextId");
        ApiException.requireText(flowId, "A secured outbound call requires a flowId");
        // The cached token is already scoped to the counterparty, so counterpartyDid is not needed here.
        var url = baseUrl + "/tokens/" + participantContextId + "/" + flowId;
        var request = new Request.Builder().url(url).get().build();
        try (var response = http.execute(request)) {
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Siglet returned HTTP " + response.code() + " for flow " + flowId);
            }
            var body = response.body() == null ? "" : response.body().string();
            var parsed = mapper.readValue(body, SigletTokenResponse.class);
            if (parsed.token() == null || parsed.endpoint() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Siglet token response for flow " + flowId + " is missing token or endpoint");
            }
            return new ResolvedToken(parsed.token(), parsed.endpoint());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not reach siglet for flow " + flowId + ": " + e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SigletTokenResponse(String token, String endpoint) {
    }
}
