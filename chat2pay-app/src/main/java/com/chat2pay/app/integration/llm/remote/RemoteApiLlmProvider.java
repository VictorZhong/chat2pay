package com.chat2pay.app.integration.llm.remote;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.ChatCompletionPayloads;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RemoteApiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteApiLlmProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxCompletionTokens;
    private final ObjectMapper mapper;
    private final RestClient http;

    public RemoteApiLlmProvider(
            ObjectMapper mapper,
            @Value("${chat2pay.remote.base-url:}") String baseUrl,
            @Value("${chat2pay.remote.api-key:}") String apiKey,
            @Value("${chat2pay.remote.model:gpt-5.4}") String model,
            @Value("${chat2pay.remote.max-completion-tokens:2048}") int maxCompletionTokens) {
        this.mapper = mapper;
        this.baseUrl = nullIfBlank(baseUrl);
        this.apiKey = nullIfBlank(apiKey);
        this.model = nullIfBlank(model) == null ? "gpt-5.4" : model;
        this.maxCompletionTokens = maxCompletionTokens;
        this.http = RestClient.builder().build();
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.REMOTE_API;
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && apiKey != null;
    }

    @Override
    public LlmCompletionResponse complete(LlmCompletionRequest request) {
        if (!isAvailable()) {
            throw new IllegalStateException("Remote API LLM provider is not configured.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("max_completion_tokens", request.maxTokens() == null ? maxCompletionTokens : request.maxTokens());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("messages", ChatCompletionPayloads.messages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", ChatCompletionPayloads.tools(request.tools()));
        }
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            body.put("tool_choice", request.toolChoice());
        }

        String url = baseUrl + "/chat/completions";
        log.debug("LLM Remote request: url={} provider={} model={} body={}",
                url, providerType(), model, toJson(body));
        String rawResponse;
        try {
            rawResponse = http.post()
                    .uri(url)
                    .headers(h -> {
                        h.setBearerAuth(apiKey);
                        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.debug("LLM Remote error response: url={} provider={} model={} status={} headers={} body={}",
                    url, providerType(), model, ex.getStatusCode().value(),
                    ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw ex;
        }
        log.debug("LLM Remote response: url={} provider={} model={} body={}",
                url, providerType(), model, rawResponse);

        ChatCompletionResponse resp = readJson(rawResponse, ChatCompletionResponse.class);

        ChoiceMessage message = resp != null && resp.choices() != null && !resp.choices().isEmpty()
                ? resp.choices().get(0).message()
                : null;
        List<ToolCall> toolCalls = message != null && message.toolCalls() != null
                ? message.toolCalls().stream()
                    .filter(tc -> tc.function() != null && tc.function().name() != null)
                    .map(tc -> new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()))
                    .toList()
                : List.of();
        String content = extractText(message == null ? null : message.content());
        log.debug("LLM Remote parsed response: model={} contentChars={} toolCalls={} toolCallDetails={}",
                model, content == null ? 0 : content.length(), toolCalls.size(), toolCalls);
        return new LlmCompletionResponse(LlmProviderType.REMOTE_API, model, content, toolCalls);
    }

    private <T> T readJson(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return mapper.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new RestClientException("Failed to parse LLM response as " + type.getSimpleName(), ex);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private static String extractText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?,?> map) {
                    Object value = map.get("text");
                    if (value != null) text.append(value);
                }
            }
            return text.toString();
        }
        return "";
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(ChoiceMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceMessage(String role, Object content, @JsonAlias("tool_calls") List<RawToolCall> toolCalls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawToolCall(String id, RawFunction function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawFunction(String name, String arguments) {}
}
