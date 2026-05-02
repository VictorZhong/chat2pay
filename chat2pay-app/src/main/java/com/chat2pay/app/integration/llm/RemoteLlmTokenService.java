package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.RemoteAuth;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class RemoteLlmTokenService {

    private final Chat2PayProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    private volatile CachedToken cachedToken;

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteLlmTokenService(Chat2PayProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, Clock.systemUTC());
    }

    RemoteLlmTokenService(Chat2PayProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.clock = clock;
    }

    public String issueToken() {
        RemoteAuth auth = properties.getLlm().getRemote().getAuth();
        if (auth.getTokenUrl() == null || auth.getTokenUrl().isBlank()) {
            throw new LlmUnavailableException("Remote LLM token URL is not configured.");
        }
        if (auth.getUsername() == null || auth.getPassword() == null) {
            throw new LlmUnavailableException("Remote LLM auth credentials are not configured.");
        }

        CachedToken current = cachedToken;
        Instant now = clock.instant();
        if (current != null && current.expiresAt.isAfter(now)) {
            return current.token;
        }

        TokenResponse response;
        try {
            response = restClient.post()
                    .uri(auth.getTokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "input_token_state", Map.of(
                                    "token_type", "CREDENTIAL",
                                    "username", auth.getUsername(),
                                    "password", auth.getPassword()),
                            "output_token_state", Map.of(
                                    "token_type", "JWT",
                                    "subject_confirmation", "BEARER")))
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (Exception exception) {
            throw new LlmUnavailableException("Failed to obtain remote LLM trust token.", exception);
        }

        if (response == null || response.issuedToken() == null || response.issuedToken().isBlank()) {
            throw new LlmUnavailableException("Remote LLM token translator returned an empty token.");
        }

        long ttlSeconds = Math.max(60, auth.getTokenTtlSeconds());
        cachedToken = new CachedToken(response.issuedToken(), now.plus(Duration.ofSeconds(ttlSeconds)));
        return response.issuedToken();
    }

    void invalidateCache() {
        cachedToken = null;
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    private record TokenResponse(@JsonProperty("issued_token") String issuedToken) {
    }
}
