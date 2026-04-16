package com.chat2pay.app.integration.downstream.payee;

import com.chat2pay.app.application.journey.RegisteredPayeeDirectoryClient;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.RegisteredPayee;
import com.chat2pay.app.integration.downstream.DownstreamHeadersFactory;
import com.chat2pay.app.integration.downstream.auth.DownstreamTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@ConditionalOnProperty(prefix = "chat2pay.downstream", name = "mock-enabled", havingValue = "false")
public class HttpRegisteredPayeeDirectoryClient implements RegisteredPayeeDirectoryClient {

    private final Chat2PayProperties properties;
    private final DownstreamTokenService downstreamTokenService;
    private final DownstreamHeadersFactory downstreamHeadersFactory;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpRegisteredPayeeDirectoryClient(
            Chat2PayProperties properties,
            DownstreamTokenService downstreamTokenService,
            DownstreamHeadersFactory downstreamHeadersFactory,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.downstreamTokenService = downstreamTokenService;
        this.downstreamHeadersFactory = downstreamHeadersFactory;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RegisteredPayee> listRegisteredPayees(Profile profile) {
        String samlToken = downstreamTokenService.obtainSamlToken(profile);
        HttpHeaders headers = downstreamHeadersFactory.buildHeaders(samlToken);
        String url = UriComponentsBuilder.fromUriString(properties.getDownstream().getPayee().getUrl())
                .queryParam("payeeCategory", properties.getDownstream().getPayee().getPayeeCategory())
                .queryParam("payeeType", properties.getDownstream().getPayee().getPayeeType())
                .toUriString();

        String body = restClient.get()
                .uri(url)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            List<RegisteredPayee> payees = new ArrayList<>();
            collectPayees(root, payees);
            return payees;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse payee list response.", exception);
        }
    }

    private void collectPayees(JsonNode node, List<RegisteredPayee> payees) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectPayees(child, payees));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        JsonNode commonPayeeDetail = node.get("commonPayeeDetail");
        if (commonPayeeDetail != null && commonPayeeDetail.isObject() && commonPayeeDetail.get("name") != null) {
            String payeeIdIndex = extractPayeeIdIndex(node);
            if (payeeIdIndex != null) {
                payees.add(new RegisteredPayee(
                        payeeIdIndex,
                        commonPayeeDetail.path("payeeType").asText(),
                        commonPayeeDetail.path("name").asText(),
                        buildDescription(node)));
            }
        }

        node.elements().forEachRemaining(child -> collectPayees(child, payees));
    }

    private String extractPayeeIdIndex(JsonNode node) {
        JsonNode direct = node.get("payeeIdIndex");
        if (direct != null && !direct.isNull() && !direct.asText().isBlank()) {
            return direct.asText();
        }

        var iterator = node.fields();
        while (iterator.hasNext()) {
            JsonNode child = iterator.next().getValue();
            if (child.isObject()) {
                String nested = extractPayeeIdIndex(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String buildDescription(JsonNode node) {
        JsonNode proxy = node.get("proxyPayeeDetail");
        if (proxy != null && proxy.isObject()) {
            return proxy.path("proxyType").asText() + " • " + proxy.path("proxyIdentifier").asText();
        }
        JsonNode individual = node.get("individualPayeeDetail");
        if (individual != null && individual.isObject()) {
            return individual.path("bankName").asText() + " • " + individual.path("payeeCountry").asText();
        }
        return "Registered payee";
    }
}
