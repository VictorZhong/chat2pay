package com.chat2pay.app.integration.downstream;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "chat2pay.downstream", name = "mock-enabled", havingValue = "false")
public class HttpDomesticPaymentClient implements DomesticPaymentClient {

    private final Chat2PayProperties properties;
    private final DownstreamTokenService downstreamTokenService;
    private final DownstreamHeadersFactory downstreamHeadersFactory;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpDomesticPaymentClient(
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
    public PaymentConfirmationResult confirmDomesticPayment(Profile profile, PaymentDraft draft) {
        String samlToken = downstreamTokenService.obtainSamlToken(profile);
        HttpHeaders headers = downstreamHeadersFactory.buildHeaders(samlToken);

        String body = restClient.post()
                .uri(properties.getDownstream().getPayment().getConfirmUrl())
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(buildPayload(draft))
                .retrieve()
                .body(String.class);

        try {
            Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {
            });
            String transferReference = String.valueOf(payload.getOrDefault("transferReference", payload.getOrDefault("reference", "UNKNOWN")));
            return new PaymentConfirmationResult(transferReference, payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse confirm payment response.", exception);
        }
    }

    private ConfirmDomesticPaymentRequest buildPayload(PaymentDraft draft) {
        return new ConfirmDomesticPaymentRequest(
                new ConfirmDomesticPaymentRequest.DebitAccount(
                        new ConfirmDomesticPaymentRequest.DebitAccountIdentifier(
                                properties.getDownstream().getPayment().getDebitAccountAcn(),
                                properties.getDownstream().getPayment().getProductCategoryCode()),
                        properties.getDownstream().getPayment().getDefaultCurrency()),
                new ConfirmDomesticPaymentRequest.TransactionAmount(
                        draft.getAmount(),
                        draft.getCurrency()),
                new ConfirmDomesticPaymentRequest.TransactionSchedule(
                        "NOW",
                        LocalDate.now().toString()),
                Map.of(),
                draft.getPayeeType(),
                draft.getPayeeIdIndex(),
                false,
                new ConfirmDomesticPaymentRequest.CreditAmount(draft.getCurrency()));
    }
}
