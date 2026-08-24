package org.metaform.certo.common.security.exchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import okhttp3.FormBody;
import okhttp3.Request;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.security.SecurityProperties;
import org.metaform.certo.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Mints the bearer this runtime sends to siglet, by exchanging the pod's Kubernetes ServiceAccount token at
 * the broker's RFC 8693 endpoint ({@code POST /token}, form-encoded, grant type
 * {@code urn:ietf:params:oauth:grant-type:token-exchange}). The {@code resource} names the participant
 * context the token is requested for — the caller supplies it, because it differs per siglet call site.
 *
 * <p>Both siglet call sites share this one component. When
 * {@code certo.security.token-exchange.enabled} is false every exchange returns {@link Optional#empty()} and
 * the siglet calls go out unauthenticated, exactly as before the feature existed.
 *
 * <p>Nothing is cached: an exchange runs per siglet call, and the subject token is re-read from disk each
 * time because the kubelet rotates a projected ServiceAccount token in place.
 */
@Component
public class TokenExchangeClient {

    private static final Logger LOG = LoggerFactory.getLogger(TokenExchangeClient.class);

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final SecurityProperties.TokenExchange config;

    public TokenExchangeClient(SecurityProperties properties, RetryingHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.tokenExchange();
        if (config.enabled()) {
            // Fail fast: a half-configured exchange would otherwise surface as a 502 on the first
            // protocol call rather than at startup.
            require(config.url(), "url");
            require(config.scope(), "scope");
            require(config.audience(), "audience");
            require(config.verifyResource(), "verify-resource");
            require(config.subjectTokenPath(), "subject-token-path");
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "certo.security.token-exchange." + key + " must be set when token exchange is enabled");
        }
    }

    /** Whether siglet calls authenticate at all. */
    public boolean enabled() {
        return config.enabled();
    }

    /** The {@code resource} the tenant-independent {@code /tokens/verify} call exchanges for. */
    public String verifyResource() {
        return config.verifyResource();
    }

    /**
     * Exchanges the subject token for an access token scoped to {@code resource}, or empty when token
     * exchange is disabled. Throws {@link ApiException} with {@code 502} when the broker cannot be reached or
     * rejects the exchange — an infrastructure failure, not a caller error.
     */
    public Optional<String> accessTokenFor(String resource) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        var form = new FormBody.Builder()
                .add("grant_type", GRANT_TYPE)
                .add("subject_token", subjectToken())
                .add("subject_token_type", SUBJECT_TOKEN_TYPE)
                .add("resource", resource)
                .add("scope", config.scope())
                .add("audience", config.audience())
                .build();
        var request = new Request.Builder().url(config.url()).post(form).build();
        try (var response = http.execute(request)) {
            if (!response.isSuccessful()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Token exchange returned HTTP " + response.code() + " for resource " + resource);
            }
            var body = response.body() == null ? "" : response.body().string();
            var parsed = mapper.readValue(body, TokenExchangeResponse.class);
            if (parsed.accessToken() == null || parsed.accessToken().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Token exchange response for resource " + resource + " is missing an access_token");
            }
            return Optional.of(parsed.accessToken());
        } catch (IOException e) {
            // Log the connectivity detail (broker host/port) server-side only, as the siglet clients do.
            LOG.warn("Could not reach the token-exchange broker: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not obtain a token-exchange token for resource " + resource);
        }
    }

    /** Reads the ServiceAccount JWT afresh — a projected token is rotated in place by the kubelet. */
    private String subjectToken() {
        try {
            var token = Files.readString(Path.of(config.subjectTokenPath())).strip();
            if (token.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "The token-exchange subject token is empty");
            }
            return token;
        } catch (IOException e) {
            // The path is deployment topology; keep it out of the response.
            LOG.warn("Could not read the token-exchange subject token from {}: {}",
                    config.subjectTokenPath(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read the token-exchange subject token");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenExchangeResponse(@JsonProperty("access_token") String accessToken) {
    }
}
