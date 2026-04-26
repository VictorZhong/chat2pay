package com.chat2pay.app.integration.downstream.payee;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.auth.DownstreamAuthService;
import com.chat2pay.app.persistence.repository.ProfileStore.SourceSystemContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HttpRegisteredPayeeClient implements RegisteredPayeeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRegisteredPayeeClient.class);

    private final Chat2PayProperties properties;
    private final DownstreamAuthService auth;
    private final ObjectMapper mapper;
    private final RestClient http;

    private final Map<String, CachedPayees> cachedPayeesByProfile = new HashMap<>();

    public HttpRegisteredPayeeClient(Chat2PayProperties properties,
                                     DownstreamAuthService auth,
                                     ObjectMapper mapper) {
        this.properties = properties;
        this.auth = auth;
        this.mapper = mapper;
        this.http = RestClient.builder()
                .requestFactory(requestFactory(properties.downstreamRequestTimeoutMs()))
                .build();
    }

    @Override
    public List<DownstreamPayee> loadPayees(String profileId) {
        if (properties.downstreamMockEnabled()) {
            log.debug("Downstream payee request skipped because mock mode is enabled: profileId={}", profileId);
            return List.of();
        }
        synchronized (cachedPayeesByProfile) {
            CachedPayees cached = cachedPayeesByProfile.get(profileId);
            if (cached != null && isCacheValid(cached)) {
                log.debug("Downstream payee cache hit: profileId={} count={}", profileId, cached.payees().size());
                return cached.payees();
            }
        }

        String samlToken = auth.login(profileId);
        String url = payeeUrl();
        HttpHeaders headers = auth.authenticatedHeaders(profileId, samlToken, SourceSystemContext.PAYEE_LOOKUP);
        log.debug("Downstream payee HTTP request: profileId={} method=GET url={} headers={} body=<none>",
                profileId, url, headers);
        ResponseEntity<String> response;
        try {
            response = http.get()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Downstream payee HTTP error response: profileId={} method=GET url={} status={} headers={} body={}",
                    profileId, url, ex.getStatusCode().value(), ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw new RestClientException("Payment payee lookup failed with HTTP "
                    + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(), ex);
        }
        log.debug("Downstream payee HTTP response: profileId={} method=GET url={} status={} headers={} body={}",
                profileId, url, response.getStatusCode().value(), response.getHeaders(), response.getBody());
        JsonNode body = readJson(response.getBody());
        log.debug("Downstream payee raw response summary: profileId={} topLevelFields={}",
                profileId, topLevelFields(body));
        List<DownstreamPayee> parsed = parsePayees(body);
        synchronized (cachedPayeesByProfile) {
            cachedPayeesByProfile.put(profileId, new CachedPayees(parsed, Instant.now()));
        }
        log.debug("Downstream payee response parsed: profileId={} count={} payees={}",
                profileId, parsed.size(), parsed);
        return parsed;
    }

    private boolean isCacheValid(CachedPayees cached) {
        return !cached.payees().isEmpty()
                && cached.cachedAt().plusSeconds(properties.downstreamPayeeCacheTtlSeconds()).isAfter(Instant.now());
    }

    private record CachedPayees(List<DownstreamPayee> payees, Instant cachedAt) {}

    private String payeeUrl() {
        Chat2PayProperties.DownstreamProperties downstream = properties.downstream();
        if (downstream == null || downstream.payeeUrl() == null || downstream.payeeUrl().isBlank()) {
            throw new IllegalStateException("chat2pay.downstream.payee-url must be configured in yaml or environment.");
        }
        return downstream.payeeUrl();
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new RestClientException("Payment payee response was not valid JSON.", ex);
        }
    }

    private static List<String> topLevelFields(JsonNode body) {
        if (body == null || !body.isObject()) return List.of();
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<DownstreamPayee> parsePayees(JsonNode body) {
        if (body == null || body.isNull()) return List.of();
        List<DownstreamPayee> result = new ArrayList<>();
        JsonNode financialAddressList = body.path("financialAddressDTOList");
        if (financialAddressList.isArray()) {
            parseFinancialAddressFormat(financialAddressList, result);
        } else {
            List<JsonNode> rawPayees = new ArrayList<>();
            collectPayees(body, rawPayees);
            for (JsonNode raw : rawPayees) addGenericPayee(raw, result);
        }
        return List.copyOf(result);
    }

    private void parseFinancialAddressFormat(JsonNode contacts, List<DownstreamPayee> result) {
        for (JsonNode contact : contacts) {
            String name = firstNonBlank(text(contact, "nickName"), text(contact, "contactFullName"));
            JsonNode addresses = contact.path("financialAddressList");
            if (!addresses.isArray()) continue;
            for (JsonNode address : addresses) {
                String identifierType = text(address, "identifierType");
                String paymentType = text(address, "paymentType");
                if (!("BACD".equals(identifierType) || "zzzzBA".equals(identifierType))
                        || !"DOMESTIC".equals(paymentType)) {
                    continue;
                }
                JsonNode subAccounts = address.path("subAccount");
                if ("zzzzBA".equals(identifierType) && subAccounts.isArray() && subAccounts.size() > 0) {
                    for (JsonNode sub : subAccounts) {
                        addPayee(
                                result,
                                firstNonBlank(name, ""),
                                firstNonBlank(text(sub, "payeeType"), text(address, "payeeType"), ""),
                                firstNonBlank(text(sub, "identifierCode"), text(address, "identifierCode"), ""),
                                firstNonBlank(text(sub, "bankName"), text(address, "bankName"), ""),
                                firstNonBlank(text(sub, "identifierNumber"), text(sub, "formattedAccountNumber"),
                                        text(address, "identifierNumber"), text(address, "formattedAccountNumber"), ""),
                                firstNonBlank(text(sub, "accountProductType"), text(address, "accountProductType"), ""),
                                firstNonBlank(text(sub, "accountProductCode"), text(address, "accountProductCode"), ""),
                                firstNonBlank(text(sub, "remittanceCurrencyCode"), text(address, "remittanceCurrencyCode"), ""),
                                firstNonBlank(text(sub, "addressId"), text(address, "addressId"))
                        );
                    }
                } else {
                    addPayee(
                            result,
                            firstNonBlank(name, ""),
                            firstNonBlank(text(address, "payeeType"), ""),
                            firstNonBlank(text(address, "identifierCode"), ""),
                            firstNonBlank(text(address, "bankName"), ""),
                            firstNonBlank(text(address, "identifierNumber"), text(address, "formattedAccountNumber"), ""),
                            firstNonBlank(text(address, "accountProductType"), ""),
                            firstNonBlank(text(address, "accountProductCode"), ""),
                            firstNonBlank(text(address, "remittanceCurrencyCode"), ""),
                            text(address, "addressId")
                    );
                }
            }
        }
    }

    private void collectPayees(JsonNode node, List<JsonNode> results) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectPayees(item, results);
            return;
        }
        if (!node.isObject()) return;

        JsonNode common = node.path("commonPayeeDetail");
        if (common.isObject() && text(common, "name") != null) {
            JsonNode subAccounts = firstArray(node, "subAccount", "subAccountList", "subAccounts");
            if (subAccounts != null && subAccounts.size() > 0) {
                for (JsonNode sub : subAccounts) {
                    if (!sub.isObject()) continue;
                    ObjectNode expanded = mapper.createObjectNode();
                    expanded.set("commonPayeeDetail", common);
                    expanded.set("individualPayeeDetail", sub);
                    expanded.put("payeeIdIndex", firstNonBlank(text(sub, "addressId"), extractPayeeIdIndex(node), ""));
                    results.add(expanded);
                }
            } else {
                results.add(node);
            }
        }

        for (Map.Entry<String, JsonNode> field : node.properties()) {
            collectPayees(field.getValue(), results);
        }
    }

    private void addGenericPayee(JsonNode node, List<DownstreamPayee> result) {
        JsonNode common = node.path("commonPayeeDetail");
        JsonNode individual = node.path("individualPayeeDetail");
        addPayee(
                result,
                text(common, "name"),
                firstNonBlank(text(common, "payeeType"), text(individual, "payeeType"), ""),
                firstNonBlank(text(individual, "bankCode"), text(individual, "identifierCode"), ""),
                firstNonBlank(text(individual, "bankName"), ""),
                firstNonBlank(text(individual, "accountNumber"), text(individual, "identifierNumber"),
                        text(individual, "formattedAccountNumber"), ""),
                firstNonBlank(text(individual, "accountProductType"), ""),
                firstNonBlank(text(individual, "accountProductCode"), ""),
                firstNonBlank(text(individual, "remittanceCurrencyCode"), ""),
                extractPayeeIdIndex(node)
        );
    }

    private void addPayee(List<DownstreamPayee> result,
                          String name,
                          String payeeType,
                          String bankCode,
                          String bankName,
                          String accountNumber,
                          String accountProductType,
                          String accountProductCode,
                          String remittanceCurrencyCode,
                          String payeeIdIndex) {
        if (isBlank(name) || isBlank(payeeIdIndex)) return;
        if (result.stream().anyMatch(existing -> existing.payeeIdIndex().equals(payeeIdIndex))) return;
        String displayLabel = isBlank(accountProductType)
                ? firstNonBlank(accountNumber, "")
                : accountProductType + (isBlank(accountNumber) ? "" : " - " + accountNumber);
        result.add(new DownstreamPayee(
                result.size() + 1,
                payeeIdIndex,
                name,
                firstNonBlank(payeeType, ""),
                firstNonBlank(bankCode, ""),
                firstNonBlank(bankName, ""),
                firstNonBlank(accountNumber, ""),
                firstNonBlank(accountProductType, ""),
                firstNonBlank(accountProductCode, ""),
                firstNonBlank(remittanceCurrencyCode, ""),
                displayLabel
        ));
    }

    private JsonNode firstArray(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isArray()) return value;
        }
        return null;
    }

    private String extractPayeeIdIndex(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String direct = firstNonBlank(text(node, "payeeIdIndex"), text(node, "addressId"));
        if (direct != null) return direct;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String nested = extractPayeeIdIndex(field.getValue());
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String nested = extractPayeeIdIndex(item);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }
}
