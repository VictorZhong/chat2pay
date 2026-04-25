package com.chat2pay.app.integration.downstream.domestic;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.auth.DownstreamAuthService;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient.DownstreamPayee;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.ProfileStore.RuntimeProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    private static final String DEFAULT_CONFIRM_URL =
            "https://dcc-hk-hbap-mvmny-domestic-payments-papi-3.zzzz-dsvc-papi-hk01-hbap-cert.svc.default.shp.ape1.pre-prod.aws.cloud.zzzz/confirm-domestic-payments";

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
            log.debug("Mock domestic payment confirm: profileId={} payeeId={} amount={} date={}",
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
        log.debug("Downstream domestic confirm request: profileId={} url={} payeeId={} payeeName={} amount={} date={} currency={}",
                request.profileId(),
                confirmUrl(),
                request.payeeIdIndex(),
                firstNonBlank(request.payeeName(), payee.name()),
                request.amount(),
                request.paymentDate(),
                profile.paymentCurrencyOrDefault(properties.defaultCurrencyOrHkd()));
        log.debug("Downstream domestic confirm payload: profileId={} payload={}", request.profileId(), payload);
        ResponseEntity<String> response = http.post()
                .uri(confirmUrl())
                .headers(h -> h.addAll(auth.authenticatedHeaders(request.profileId(), samlToken)))
                .body(payload)
                .retrieve()
                .toEntity(String.class);

        Map<String, Object> responseBody = parseResponse(response.getBody());
        log.debug("Downstream domestic confirm response: profileId={} status={} responseKeys={} body={}",
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
                        + profile.paymentCurrencyOrDefault(properties.defaultCurrencyOrHkd()) + ")."
        );
    }

    private Map<String, Object> buildPayload(DomesticPaymentRequest request,
                                             DownstreamPayee payee,
                                             RuntimeProfile profile) {
        String currency = profile.paymentCurrencyOrDefault(properties.defaultCurrencyOrHkd());

        Map<String, Object> debitAccountIdentifier = Map.of(
                "accountNumber", profile.requiredDebitAccountNumber(),
                "productCategoryCode", profile.debitProductCategoryCodeOrDefault()
        );
        Map<String, Object> debitAccount = Map.of(
                "debitAccountIdentifier", debitAccountIdentifier,
                "currency", currency
        );
        LocalDate scheduleDate = request.paymentDate() == null ? LocalDate.now() : request.paymentDate();
        BigDecimal amount = BigDecimal.valueOf(request.amount()).setScale(2, RoundingMode.HALF_UP);

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
            return DEFAULT_CONFIRM_URL;
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
        if (request.amount() == null || request.amount() <= 0) {
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
