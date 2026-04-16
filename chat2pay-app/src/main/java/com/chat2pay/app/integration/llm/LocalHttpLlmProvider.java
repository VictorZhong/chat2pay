package com.chat2pay.app.integration.llm;

import com.chat2pay.app.application.journey.HeuristicMessageAnalyzer;
import com.chat2pay.app.application.journey.LlmProvider;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.MessageAnalysis;
import com.chat2pay.app.domain.Profile;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LocalHttpLlmProvider implements LlmProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalHttpLlmProvider.class);

    private final Chat2PayProperties properties;
    private final HeuristicMessageAnalyzer heuristicMessageAnalyzer;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LocalHttpLlmProvider(
            Chat2PayProperties properties,
            HeuristicMessageAnalyzer heuristicMessageAnalyzer,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.heuristicMessageAnalyzer = heuristicMessageAnalyzer;
        this.restClient = restClientBuilder.baseUrl(properties.getLlm().getBaseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public MessageAnalysis analyze(Profile profile, String userMessage) {
        MessageAnalysis fallback = heuristicMessageAnalyzer.analyze(userMessage);
        if (!properties.getLlm().isEnabled()) {
            return fallback;
        }

        try {
            LlmChatResponse response = restClient.post()
                    .uri(properties.getLlm().getChatPath())
                    .body(new LlmChatRequest(buildPrompt(profile, userMessage), null, List.of()))
                    .retrieve()
                    .body(LlmChatResponse.class);
            MessageAnalysis parsed = parseResponse(response);
            return fallback.mergePreferNonNull(parsed);
        } catch (Exception exception) {
            LOGGER.debug("Local LLM request failed, using heuristic fallback", exception);
            return fallback;
        }
    }

    private String buildPrompt(Profile profile, String userMessage) {
        return """
                Extract structured intent for a banking POC.
                Supported flow: domestic payment to an existing registered payee only.
                Current username/payment10: %s

                Return JSON only with this shape:
                {
                  "supportedPaymentIntent": boolean,
                  "unsupportedRequest": boolean,
                  "confirmIntent": boolean,
                  "cancelIntent": boolean,
                  "payeeName": string|null,
                  "amount": number|null,
                  "currency": string|null,
                  "note": string|null
                }

                User message:
                %s
                """.formatted(profile.username(), userMessage);
    }

    private MessageAnalysis parseResponse(LlmChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            return null;
        }

        String body = response.response().trim();
        String jsonCandidate = extractJsonObject(body);
        if (jsonCandidate == null) {
            return null;
        }

        try {
            LlmStructuredResponse parsed = objectMapper.readValue(jsonCandidate, LlmStructuredResponse.class);
            return new MessageAnalysis(
                    parsed.supportedPaymentIntent(),
                    parsed.unsupportedRequest(),
                    parsed.confirmIntent(),
                    parsed.cancelIntent(),
                    parsed.payeeName(),
                    parsed.amount(),
                    parsed.currency(),
                    parsed.note());
        } catch (Exception exception) {
            LOGGER.debug("Unable to parse structured local LLM response", exception);
            return null;
        }
    }

    private String extractJsonObject(String responseText) {
        int start = responseText.indexOf('{');
        int end = responseText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return responseText.substring(start, end + 1);
    }

    private record LlmChatRequest(
            String message,
            @JsonProperty("session_id") String sessionId,
            List<String> attachments) {
    }

    private record LlmChatResponse(String response) {
    }

    private record LlmStructuredResponse(
            boolean supportedPaymentIntent,
            boolean unsupportedRequest,
            boolean confirmIntent,
            boolean cancelIntent,
            String payeeName,
            java.math.BigDecimal amount,
            String currency,
            String note) {
    }
}
