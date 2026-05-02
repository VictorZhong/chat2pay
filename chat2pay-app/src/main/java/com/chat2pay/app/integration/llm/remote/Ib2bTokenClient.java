package com.chat2pay.app.integration.llm.remote;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.Ib2bProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exchanges a static username/password for a short-lived JWT against the IB2B
 * token translator, then caches the result. Used to add the
 * {@code X-zzzz-E2E-Trust-Token} header on remote-LLM calls whose model entry
 * declares {@code auth: IB2B}.
 *
 * <p>The translator is corporate-internal so no proxy is configured here.
 */
@Component
public class Ib2bTokenClient {

    private static final Logger log = LoggerFactory.getLogger(Ib2bTokenClient.class);
    private static final int DEFAULT_TTL_SECONDS = 600;

    private final Ib2bProperties config;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant cachedExpiresAt = Instant.EPOCH;

    public Ib2bTokenClient(Chat2PayProperties properties, ObjectMapper mapper) {
        this.config = properties.remote() == null ? null : properties.remote().ib2b();
        this.mapper = mapper;
        this.http = RestClient.builder().build();
    }

    public boolean isConfigured() {
        return config != null
                && nonBlank(config.tokenUrl())
                && nonBlank(config.username())
                && nonBlank(config.password());
    }

    /** Fresh, in-cache token if available; otherwise call the translator. */
    public String currentToken() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "IB2B token translator not configured (chat2pay.remote.ib2b.{token-url,username,password}).");
        }
        return refresh(false);
    }

    /** Force a fresh exchange (e.g. on 401 from the LLM endpoint). */
    public String refresh() {
        return refresh(true);
    }

    private String refresh(boolean force) {
        refreshLock.lock();
        try {
            Instant now = Instant.now();
            String token = cachedToken;
            if (!force && token != null && now.isBefore(cachedExpiresAt)) return token;

            Map<String, Object> body = Map.of(
                    "input_token_state", Map.of(
                            "token_type", "CREDENTIAL",
                            "username", config.username(),
                            "password", config.password()
                    ),
                    "output_token_state", Map.of(
                            "token_type", "JWT",
                            "subject_confirmation", "BEARER"
                    )
            );

            String raw = http.post()
                    .uri(config.tokenUrl())
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);

            TokenResponse parsed;
            try {
                parsed = mapper.readValue(raw, TokenResponse.class);
            } catch (JsonProcessingException ex) {
                throw new RestClientException("IB2B token response was not valid JSON", ex);
            }
            if (parsed == null || !nonBlank(parsed.issuedToken())) {
                throw new RestClientException("IB2B token response did not include issued_token");
            }
            int ttlSeconds = config.tokenTtlSecondsOr(DEFAULT_TTL_SECONDS);
            this.cachedToken = parsed.issuedToken();
            this.cachedExpiresAt = Instant.now().plus(Duration.ofSeconds(ttlSeconds));
            log.debug("IB2B token refreshed; ttlSeconds={} tokenChars={}",
                    ttlSeconds, parsed.issuedToken().length());
            return parsed.issuedToken();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Helper for X-zzzz-Request-Correlation-Id. */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("issued_token") String issuedToken) {}
}
