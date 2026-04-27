package com.chat2pay.app.integration.downstream.payee;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.auth.DownstreamAuthService;
import com.chat2pay.app.persistence.repository.ProfileStore.SourceSystemContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
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
        log.debug("Downstream payee response parsed: profileId={} payeeCount={} accountCount={}",
                profileId, parsed.size(), parsed.stream().mapToInt(p -> p.accounts().size()).sum());
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

    /**
     * Parse the bank's payee response into a hierarchical (contact → account) structure.
     * For each contact, walk financialAddressList. If a financial address has a non-empty
     * subAccount array, the parent itself is treated as a wrapper (an "Integrated Account")
     * and only the sub-accounts are kept as payable accounts. Otherwise the address itself
     * becomes a payable account.
     */
    private List<DownstreamPayee> parsePayees(JsonNode body) {
        if (body == null || body.isNull()) return List.of();
        List<DownstreamPayee> result = new ArrayList<>();
        JsonNode contacts = body.path("financialAddressDTOList");
        if (!contacts.isArray()) return List.of();

        for (JsonNode contact : contacts) {
            String contactId = text(contact, "contactId");
            String contactIdentifier = text(contact, "contactIdentifier");
            String nickName = text(contact, "nickName");
            String contactFullName = text(contact, "contactFullName");
            String contactType = text(contact, "contactType");
            String contactSource = text(contact, "contactSource");

            JsonNode addresses = contact.path("financialAddressList");
            if (!addresses.isArray()) continue;

            List<DownstreamAccount> accounts = new ArrayList<>();
            for (JsonNode address : addresses) {
                JsonNode subAccounts = address.path("subAccount");
                if (subAccounts.isArray() && subAccounts.size() > 0) {
                    for (JsonNode sub : subAccounts) {
                        DownstreamAccount account = parseAccount(sub, address);
                        if (account != null) accounts.add(account);
                    }
                } else {
                    DownstreamAccount account = parseAccount(address, null);
                    if (account != null) accounts.add(account);
                }
            }

            if (accounts.isEmpty()) continue;
            // Skip contacts with no usable identity at all — defensive.
            if (isBlank(nickName) && isBlank(contactFullName)) continue;

            result.add(new DownstreamPayee(
                    contactId,
                    contactIdentifier,
                    firstNonBlank(nickName, contactFullName),
                    firstNonBlank(contactFullName, nickName),
                    contactType,
                    contactSource,
                    accounts
            ));
        }
        return List.copyOf(result);
    }

    private DownstreamAccount parseAccount(JsonNode primary, JsonNode fallback) {
        String addressId = pickText(primary, fallback, "addressId");
        if (isBlank(addressId)) return null;
        return new DownstreamAccount(
                addressId,
                pickText(primary, fallback, "identifierType"),
                pickText(primary, fallback, "identifierCode"),
                pickText(primary, fallback, "identifierNumber"),
                firstNonBlank(
                        pickText(primary, fallback, "formattedAccountNumber"),
                        pickText(primary, fallback, "identifierNumber"),
                        ""),
                pickText(primary, fallback, "bankCountryCode"),
                pickText(primary, fallback, "bankName"),
                pickText(primary, fallback, "addrFullName"),
                pickText(primary, fallback, "accountProductType"),
                pickText(primary, fallback, "accountProductCode"),
                pickText(primary, fallback, "payeeType"),
                pickText(primary, fallback, "paymentType"),
                pickText(primary, fallback, "payeeAccountLabel"),
                pickDecimal(primary, fallback, "accountLimit"),
                pickText(primary, fallback, "accountLimitCurrency"),
                pickText(primary, fallback, "remittanceCurrencyCode")
        );
    }

    private static String pickText(JsonNode primary, JsonNode fallback, String field) {
        String value = text(primary, field);
        if (value != null) return value;
        return fallback == null ? null : text(fallback, field);
    }

    private static BigDecimal pickDecimal(JsonNode primary, JsonNode fallback, String field) {
        BigDecimal value = decimal(primary, field);
        if (value != null) return value;
        return fallback == null ? null : decimal(fallback, field);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) return null;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
