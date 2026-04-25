package com.chat2pay.app.integration.downstream.auth;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.ProfileStore.RuntimeProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentDownstreamAuthService implements DownstreamAuthService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDownstreamAuthService.class);

    private final Chat2PayProperties properties;
    private final ProfileStore profiles;
    private final RestClient http;

    public PaymentDownstreamAuthService(Chat2PayProperties properties, ProfileStore profiles) {
        this.properties = properties;
        this.profiles = profiles;
        this.http = RestClient.builder()
                .requestFactory(requestFactory(properties.downstreamRequestTimeoutMs()))
                .build();
    }

    @Override
    public String login(String profileId) {
        if (properties.downstreamMockEnabled()) {
            log.debug("Downstream login skipped because mock mode is enabled: profileId={}", profileId);
            return "mock-saml-token";
        }
        RuntimeProfile profile = profiles.runtimeProfile(profileId);

        String url = resolveLoginUrl(profile.requiredUsername());
        var uri = UriComponentsBuilder.fromUriString(url)
                .queryParam("password", profile.requiredPassword())
                .build()
                .encode()
                .toUri();
        log.debug("Downstream login HTTP request: profileId={} method=GET url={} headers={} body=<none>",
                profileId, uri, Map.of());
        ResponseEntity<String> response;
        try {
            response = http.get()
                    .uri(uri)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Downstream login HTTP error response: profileId={} method=GET url={} status={} headers={} body={}",
                    profileId, uri, ex.getStatusCode().value(), ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw new RestClientException("Payment login failed with HTTP "
                    + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(), ex);
        }
        String body = response.getBody();
        log.debug("Downstream login HTTP response: profileId={} method=GET url={} status={} headers={} body={}",
                profileId, uri, response.getStatusCode().value(), response.getHeaders(), body);
        if (body == null || body.isBlank()) {
            throw new RestClientException("Payment login returned an empty SAML token.");
        }
        return body;
    }

    @Override
    public HttpHeaders authenticatedHeaders(String profileId, String samlToken) {
        RuntimeProfile profile = profiles.runtimeProfile(profileId);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "*/*");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-HK");
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8");
        headers.set("X-zzzz-Channel-Id", value(downstream().channelId(), "WEB"));
        headers.set("X-zzzz-Chnl-CountryCode", value(downstream().countryCode(), "HK"));
        headers.set("X-zzzz-Chnl-Group-Member", value(downstream().groupMember(), "HBAP"));
        headers.set("X-zzzz-Locale", value(downstream().locale(), "en_HK"));
        headers.set("X-zzzz-Request-Correlation-Id", UUID.randomUUID().toString());
        headers.set("X-zzzz-Session-Correlation-Id", UUID.randomUUID().toString());
        headers.set("X-zzzz-Saml3", samlToken);
        headers.set("X-zzzz-Source-System-Id",
                profile.sourceSystemIdOrDefault(value(downstream().sourceSystemId(), "11114418_O88")));
        headers.set("X-zzzz-Src-Device-Id", value(downstream().deviceId(), "192.168.1.1, 192.168.1.2"));
        headers.set("X-zzzz-Src-UserAgent", value(downstream().userAgent(),
                "Mozilla/5.0 (iPad; U; CPU OS 3_2_1 like Mac OS X; en-us) "
                        + "AppleWebKit/531.21.10 (KHTML, like Gecko) Mobile/7B405"));
        log.debug("Downstream authenticated headers prepared: profileId={} headers={}", profileId, headers);
        return headers;
    }

    private String resolveLoginUrl(String username) {
        String template = requiredUrl("chat2pay.downstream.login-url-template", downstream().loginUrlTemplate());
        String encoded = UriUtils.encodePathSegment(username, StandardCharsets.UTF_8);
        return template.replace("{}", encoded).replace("{username}", encoded);
    }

    private Chat2PayProperties.DownstreamProperties downstream() {
        return properties.downstream() == null
                ? new Chat2PayProperties.DownstreamProperties(null, null, null, null,
                        null, null, null, null, null, null, null, null, null)
                : properties.downstream();
    }

    private static String value(String raw, String defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        return raw;
    }

    private static String requiredUrl(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured in yaml or environment.");
        }
        return value;
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }
}
