package com.chat2pay.app.integration.downstream.domestic;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.auth.DownstreamAuthService;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient.DownstreamPayee;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.ProfileStore.RuntimeProfile;
import com.chat2pay.app.persistence.repository.ProfileStore.SourceSystemContext;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class HttpDomesticPaymentClient implements DomesticPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDomesticPaymentClient.class);

    private final Chat2PayProperties properties;
    private final DownstreamAuthService auth;
    private final RegisteredPayeeClient payees;
    private final ProfileStore profiles;
    private final ObjectMapper mapper;
    private final RestClient http;

    public HttpDomesticPaymentClient(Chat2PayProperties properties,
                                     DownstreamAuthService auth,
                                     RegisteredPayeeClient payees,
                                     ProfileStore profiles,
                                     ObjectMapper mapper) {
        this.properties = properties;
        this.auth = auth;
        this.payees = payees;
        this.profiles = profiles;
        this.mapper = mapper;
        this.http = RestClient.builder()
                .requestFactory(requestFactory(properties.downstreamRequestTimeoutMs()))
                .build();
    }

    @Override
    public PaymentConfirmationResult confirm(DomesticPaymentRequest request) {
        validate(request);
        if (properties.downstreamMockEnabled()) {
            log.debug("Downstream domestic confirm skipped because mock mode is enabled: profileId={} payeeId={} amount={} date={}",
                    request.profileId(), request.payeeIdIndex(), request.amount(), request.paymentDate());
            String reference = "DOM-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            return new PaymentConfirmationResult(
                    true,
                    reference,
                    200,
                    Map.of("mock", true, "profileId", request.profileId(), "payeeIdIndex", request.payeeIdIndex()),
                    "Mock domestic payment confirmed for " + request.payeeName() + "."
            );
        }

        RuntimeProfile profile = profiles.runtimeProfile(request.profileId());
        DownstreamPayee payee = payees.findByPayeeIdIndex(request.profileId(), request.payeeIdIndex())
                .orElseThrow(() -> new IllegalArgumentException("payee_id_index is not recognized."));
        String samlToken = auth.login(request.profileId());
        Map<String, Object> payload = buildPayload(request, payee, profile);
        String url = confirmUrl();
        HttpHeaders headers = auth.authenticatedHeaders(
                request.profileId(), samlToken, SourceSystemContext.DOMESTIC_PAYMENT_CONFIRM);
        log.debug("Downstream domestic confirm request summary: profileId={} url={} payeeId={} payeeName={} amount={} date={} currency={}",
                request.profileId(),
                url,
                request.payeeIdIndex(),
                firstNonBlank(request.payeeName(), payee.name()),
                request.amount(),
                request.paymentDate(),
                profile.requiredPaymentCurrency());
        log.debug("Downstream domestic confirm HTTP request: profileId={} method=POST url={} headers={} body={}",
                request.profileId(), url, headers, payload);
        ResponseEntity<String> response;
        try {
            response = http.post()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("Downstream domestic confirm HTTP error response: profileId={} method=POST url={} status={} headers={} body={}",
                    request.profileId(), url, ex.getStatusCode().value(), ex.getResponseHeaders(),
                    ex.getResponseBodyAsString());
            throw new RestClientException("Payment confirmation failed with HTTP "
                    + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(), ex);
        }
        log.debug("Downstream domestic confirm HTTP response: profileId={} method=POST url={} status={} headers={} body={}",
                request.profileId(), url, response.getStatusCode().value(), response.getHeaders(), response.getBody());

        Map<String, Object> responseBody = parseResponse(response.getBody());
        log.debug("Downstream domestic confirm parsed response: profileId={} status={} responseKeys={} body={}",
                request.profileId(), response.getStatusCode().value(), responseBody.keySet(), responseBody);
        String reference = findReference(responseBody)
                .orElse("DOM-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        return new PaymentConfirmationResult(
                true,
                reference,
                response.getStatusCode().value(),
                responseBody,
                "Payment confirmed for " + firstNonBlank(request.payeeName(), payee.name())
                        + " (" + request.amount() + " "
                        + profile.requiredPaymentCurrency() + ")."
        );
    }

    private Map<String, Object> buildPayload(DomesticPaymentRequest request,
                                             DownstreamPayee payee,
                                             RuntimeProfile profile) {
        String currency = profile.requiredPaymentCurrency();

        Map<String, Object> debitAccountIdentifier = Map.of(
                "accountNumber", profile.requiredDebitAccountNumber(),
                "productCategoryCode", profile.requiredDebitProductCategoryCode()
        );
        Map<String, Object> debitAccount = Map.of(
                "debitAccountIdentifier", debitAccountIdentifier,
                "currency", currency
        );
        LocalDate scheduleDate = request.paymentDate() == null ? LocalDate.now() : request.paymentDate();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> payload = new HashMap<>();
        payload.put("debitAccount", debitAccount);
        payload.put("transactionAmount", Map.of(
                "amount", amount,
                "currencyCode", currency
        ));
        payload.put("transactionSchedule", Map.of(
                "scheduleType", "NOW",
                "scheduledDate", scheduleDate.toString()
        ));
        payload.put("transactionMemo", Map.of());
        payload.put("payeeType", payee.payeeType());
        payload.put("pyeeIdIndex", payee.payeeIdIndex());
        payload.put("payeeSuspiciousIndicator", false);
        payload.put("creditAmount", Map.of("currencyCode", currency));
        return payload;
    }

    private Map<String, Object> parseResponse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("raw", body);
        }
    }

    private Optional<String> findReference(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                Object raw = entry.getValue();
                if ((key.contains("reference") || key.contains("confirmation"))
                        && raw instanceof String s && !s.isBlank()) {
                    return Optional.of(s);
                }
                Optional<String> nested = findReference(raw);
                if (nested.isPresent()) return nested;
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                Optional<String> nested = findReference(item);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    private String confirmUrl() {
        Chat2PayProperties.DownstreamProperties downstream = properties.downstream();
        if (downstream == null || downstream.confirmUrl() == null || downstream.confirmUrl().isBlank()) {
            throw new IllegalStateException("chat2pay.downstream.confirm-url must be configured in yaml or environment.");
        }
        return downstream.confirmUrl();
    }

    private static void validate(DomesticPaymentRequest request) {
        if (request == null) throw new IllegalArgumentException("Domestic payment request is required.");
        if (request.profileId() == null || request.profileId().isBlank()) {
            throw new IllegalArgumentException("profile_id is required.");
        }
        if (request.payeeIdIndex() == null || request.payeeIdIndex().isBlank()) {
            throw new IllegalArgumentException("payee_id_index is required.");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be a positive number.");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }
}
