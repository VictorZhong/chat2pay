package com.chat2pay.app.integration.downstream.account;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class HttpDomesticAccountClient implements DomesticAccountClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDomesticAccountClient.class);

    private final Chat2PayProperties properties;
    private final DownstreamAuthService auth;
    private final ObjectMapper mapper;
    private final RestClient http;

    private final Map<String, CachedGroups> cachedGroupsByProfile = new HashMap<>();

    public HttpDomesticAccountClient(Chat2PayProperties properties,
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
    public List<AccountGroup> loadAccounts(String profileId) {
        if (properties.downstreamMockEnabled()) {
            log.debug("Downstream account request skipped because mock mode is enabled: profileId={}", profileId);
            return mockGroups();
        }
        synchronized (cachedGroupsByProfile) {
            CachedGroups cached = cachedGroupsByProfile.get(profileId);
            if (cached != null && isCacheValid(cached)) {
                log.debug("Downstream account cache hit: profileId={} groupCount={} subAccountCount={}",
                        profileId, cached.groups().size(), cached.subAccountCount());
                return cached.groups();
            }
        }

        String samlToken = auth.login(profileId);
        String url = accountUrl();
        HttpHeaders headers = auth.authenticatedHeaders(profileId, samlToken, SourceSystemContext.DEFAULT);
        log.debug("Downstream account HTTP request: profileId={} method=GET url={} headers={} body=<none>",
                profileId, url, headers);
        ResponseEntity<String> response;
        try {
            response = http.get()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Downstream account HTTP error response: profileId={} method=GET url={} status={} headers={} body={}",
                    profileId, url, ex.getStatusCode().value(), ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw new RestClientException("Payment account lookup failed with HTTP "
                    + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(), ex);
        }
        log.debug("Downstream account HTTP response: profileId={} method=GET url={} status={} headers={} body={}",
                profileId, url, response.getStatusCode().value(), response.getHeaders(), response.getBody());

        JsonNode body = readJson(response.getBody());
        List<AccountGroup> parsed = parseGroups(body);
        synchronized (cachedGroupsByProfile) {
            cachedGroupsByProfile.put(profileId, new CachedGroups(parsed, Instant.now()));
        }
        log.debug("Downstream account response parsed: profileId={} groupCount={} subAccountCount={}",
                profileId, parsed.size(), parsed.stream().mapToInt(group -> group.subAccounts().size()).sum());
        return parsed;
    }

    private boolean isCacheValid(CachedGroups cached) {
        return cached.cachedAt().plusSeconds(properties.downstreamPayeeCacheTtlSeconds()).isAfter(Instant.now());
    }

    private record CachedGroups(List<AccountGroup> groups, Instant cachedAt) {
        int subAccountCount() {
            return groups.stream().mapToInt(group -> group.subAccounts().size()).sum();
        }
    }

    private String accountUrl() {
        Chat2PayProperties.DownstreamProperties downstream = properties.downstream();
        if (downstream == null || downstream.accountUrl() == null || downstream.accountUrl().isBlank()) {
            throw new IllegalStateException("chat2pay.downstream.account-url must be configured in yaml or environment.");
        }
        return downstream.accountUrl();
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new RestClientException("Payment account response was not valid JSON.", ex);
        }
    }

    List<AccountGroup> parseGroups(JsonNode body) {
        if (body == null || body.isNull()) return List.of();
        JsonNode accountList = body.path("accountList");
        if (!accountList.isArray()) return List.of();

        Map<String, ParentAccount> parentById = new LinkedHashMap<>();
        Map<String, List<SelectableAccount>> childrenByGroupId = new LinkedHashMap<>();

        for (JsonNode accountNode : accountList) {
            ParsedAccount parsed = parseAccount(accountNode);
            if (parsed == null || !"ACTIVE".equalsIgnoreCase(parsed.accountStatus())) continue;

            if (parsed.master()) {
                parentById.putIfAbsent(parsed.accountId(),
                        new ParentAccount(parsed.accountId(), parsed.accountDisplay(), parsed.productDescription()));
                childrenByGroupId.putIfAbsent(parsed.accountId(), new ArrayList<>());
                continue;
            }

            SelectableAccount selectable = new SelectableAccount(
                    parsed.accountId(),
                    parsed.parentAccountId(),
                    parsed.accountDisplay(),
                    parsed.productCategoryCode(),
                    parsed.productDescription(),
                    displayLabel(parsed.productDescription(), parsed.accountDisplay()),
                    parsed.ledgerBalanceCurrency(),
                    parsed.ledgerBalanceIndicator(),
                    parsed.ledgerBalanceAmount(),
                    parsed.ledgerBalanceCurrency()
            );
            String groupId = firstNonBlank(parsed.parentAccountId(), "orphan:" + parsed.accountId());
            childrenByGroupId.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(selectable);
        }

        LinkedHashSet<String> orderedGroupIds = new LinkedHashSet<>();
        orderedGroupIds.addAll(parentById.keySet());
        orderedGroupIds.addAll(childrenByGroupId.keySet());

        List<AccountGroup> groups = new ArrayList<>();
        for (String groupId : orderedGroupIds) {
            List<SelectableAccount> children = childrenByGroupId.get(groupId);
            if (children == null || children.isEmpty()) continue;
            groups.add(new AccountGroup(groupId, parentById.get(groupId), List.copyOf(children)));
        }
        return List.copyOf(groups);
    }

    private ParsedAccount parseAccount(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        JsonNode identifier = node.path("accountIdentifier");
        String accountId = text(identifier, "accountNumber");
        if (blank(accountId)) return null;
        return new ParsedAccount(
                accountId,
                text(node, "accountDisplay"),
                text(node, "productDescription"),
                text(node, "accountStatus"),
                "Y".equalsIgnoreCase(text(node, "isMaster")),
                text(node, "parentAccountId"),
                text(identifier, "productCategoryCode"),
                text(node, "ledgerBalanceIndicator"),
                decimal(node.path("ledgerBalance"), "amount"),
                text(node.path("ledgerBalance"), "currency")
        );
    }

    private List<AccountGroup> mockGroups() {
        return List.of(
                new AccountGroup(
                        "acct_master_1",
                        new ParentAccount("acct_master_1", "118-067271-833", "zzzz One"),
                        List.of(
                                mockSubAccount("acct_primary", "acct_master_1", "118-067271-833",
                                        "PVCUA", "HKD Current", "HKD", "BALANCE_AVAILABLE",
                                        new BigDecimal("999999999"), "HKD"),
                                mockSubAccount("acct_savings", "acct_master_1", "118-067271-833",
                                        "PVSAV", "HKD Savings", "HKD", "BALANCE_AVAILABLE",
                                        new BigDecimal("888888888"), "HKD"),
                                mockSubAccount("acct_notice", "acct_master_1", "118-067271-833",
                                        "PVNTA", "USD Savings", "USD", "NO_BALANCE",
                                        null, null)
                        )
                ),
                new AccountGroup(
                        "acct_master_2",
                        new ParentAccount("acct_master_2", "128-000991-001", "zzzz One"),
                        List.of(
                                mockSubAccount("acct_aud", "acct_master_2", "128-000991-001",
                                        "PVSAV", "AUD Savings", "AUD", "BALANCE_AVAILABLE",
                                        new BigDecimal("100000000"), "AUD")
                        )
                )
        );
    }

    private SelectableAccount mockSubAccount(String accountId,
                                             String parentAccountId,
                                             String accountDisplay,
                                             String productCategoryCode,
                                             String productDescription,
                                             String currency,
                                             String ledgerBalanceIndicator,
                                             BigDecimal ledgerBalanceAmount,
                                             String ledgerBalanceCurrency) {
        return new SelectableAccount(
                accountId,
                parentAccountId,
                accountDisplay,
                productCategoryCode,
                productDescription,
                displayLabel(productDescription, accountDisplay),
                currency,
                ledgerBalanceIndicator,
                ledgerBalanceAmount,
                ledgerBalanceCurrency
        );
    }

    private record ParsedAccount(
            String accountId,
            String accountDisplay,
            String productDescription,
            String accountStatus,
            boolean master,
            String parentAccountId,
            String productCategoryCode,
            String ledgerBalanceIndicator,
            BigDecimal ledgerBalanceAmount,
            String ledgerBalanceCurrency
    ) {}

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.decimalValue();
        String raw = value.asText(null);
        if (raw == null || raw.isBlank()) return null;
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

    private static String displayLabel(String productDescription, String accountDisplay) {
        if (!blank(productDescription) && !blank(accountDisplay)) {
            return productDescription.trim() + " • " + accountDisplay.trim();
        }
        return firstNonBlank(productDescription, accountDisplay);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }
}
