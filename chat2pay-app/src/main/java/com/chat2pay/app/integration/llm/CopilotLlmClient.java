package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CopilotLlmClient implements LlmClient {

    private final Chat2PayProperties properties;
    private final RestClient restClient;

    public CopilotLlmClient(Chat2PayProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getLlm().getBaseUrl()).build();
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.COPILOT;
    }

    @Override
    public LlmCompletion complete(LlmRequest request, String model) {
        if (!properties.getLlm().isEnabled()) {
            throw new LlmUnavailableException("Copilot LLM is disabled by configuration.");
        }

        try {
            CopilotChatResponse response = restClient.post()
                    .uri(properties.getLlm().getChatPath())
                    .body(new CopilotChatRequest(request.prompt(), null, List.of()))
                    .retrieve()
                    .body(CopilotChatResponse.class);
            if (response == null || response.response() == null || response.response().isBlank()) {
                throw new LlmUnavailableException("Copilot LLM returned an empty response.");
            }
            return new LlmCompletion(response.response(), LlmProviderType.COPILOT, model);
        } catch (LlmUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LlmUnavailableException("Copilot LLM request failed.", exception);
        }
    }

    private record CopilotChatRequest(
            String message,
            @JsonProperty("session_id") String sessionId,
            List<String> attachments) {
    }

    private record CopilotChatResponse(String response) {
    }
}
