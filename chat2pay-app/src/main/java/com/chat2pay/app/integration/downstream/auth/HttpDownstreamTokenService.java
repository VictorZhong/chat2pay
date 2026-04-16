package com.chat2pay.app.integration.downstream.auth;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@ConditionalOnProperty(prefix = "chat2pay.downstream", name = "mock-enabled", havingValue = "false")
public class HttpDownstreamTokenService implements DownstreamTokenService {

    private final Chat2PayProperties properties;
    private final RestClient restClient;

    public HttpDownstreamTokenService(Chat2PayProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String obtainSamlToken(Profile profile) {
        String rawUrl = properties.getDownstream().getLogin().getUrlTemplate()
                .replace("{username}", profile.username());
        String url = UriComponentsBuilder.fromUriString(rawUrl)
                .queryParam("code", properties.getDownstream().getLogin().getCode())
                .toUriString();
        return restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
    }
}
