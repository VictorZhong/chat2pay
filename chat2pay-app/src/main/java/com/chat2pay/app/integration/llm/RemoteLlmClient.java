package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;
import com.chat2pay.app.config.Chat2PayProperties.RemoteModel;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RemoteLlmClient implements LlmClient {

    private final Chat2PayProperties properties;
    private final RemoteLlmTokenService tokenService;
    private final RestClient restClient;

    public RemoteLlmClient(
            Chat2PayProperties properties,
            RemoteLlmTokenService tokenService,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.REMOTE;
    }

    @Override
    public LlmCompletion complete(LlmRequest request, String model) {
        if (!properties.getLlm().getRemote().isEnabled()) {
            throw new LlmUnavailableException("Remote LLM is disabled by configuration.");
        }
        if (model == null || model.isBlank()) {
            throw new LlmUnavailableException("Remote LLM model name is not specified for this use case.");
        }

        RemoteModel modelConfig = resolveModel(model);
        String token = tokenService.issueToken();
        String correlationId = generateCorrelationId();

        try {
            ChatCompletionResponse response = restClient.post()
                    .uri(modelConfig.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-zzzz-E2E-Trust-Token", token)
                    .header("X-zzzz-Request-Correlation-Id", correlationId)
                    .body(buildPayload(model, request))
                    .retrieve()
                    .body(ChatCompletionResponse.class);
            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                throw new LlmUnavailableException("Remote LLM returned an empty completion.");
            }
            return new LlmCompletion(content, LlmProviderType.REMOTE, model);
        } catch (LlmUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LlmUnavailableException("Remote LLM request failed for model " + model, exception);
        }
    }

    private RemoteModel resolveModel(String model) {
        return properties.getLlm().getRemote().getModels().stream()
                .filter(m -> model.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new LlmUnavailableException(
                        "Remote LLM model '" + model + "' is not declared under chat2pay.llm.remote.models."));
    }

    private Map<String, Object> buildPayload(String model, LlmRequest request) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", request.prompt())));
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        String user = properties.getLlm().getRemote().getDefaultUser();
        if (user != null && !user.isBlank()) {
            body.put("user", user);
        }
        return body;
    }

    private String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatChoice firstChoice = response.choices().get(0);
        if (firstChoice == null || firstChoice.message() == null) {
            return null;
        }
        return firstChoice.message().content();
    }

    private String generateCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ChatCompletionResponse(List<ChatChoice> choices) {
    }

    private record ChatChoice(int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    private record ChatMessage(String role, String content) {
    }
}
