package com.chat2pay.app.integration.llm.remote;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.RemoteModelProperties;
import com.chat2pay.app.config.Chat2PayProperties.RemoteProperties;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions provider for the corporate intranet
 * remote LLM. Two modes coexist for backwards compatibility:
 *
 * <ul>
 *   <li><b>Legacy single-model</b>: {@code chat2pay.remote.{base-url,api-key,
 *       model,max-completion-tokens}}. Uses Bearer auth.</li>
 *   <li><b>Multi-model registry</b>: {@code chat2pay.remote.models[]} entries
 *       keyed by {@code name}. Each entry has its own URL, auth mode
 *       ({@code BEARER} or {@code IB2B}) and per-model max-token override.
 *       The model is selected via {@link LlmCompletionRequest#model()} (or the
 *       legacy {@code chat2pay.remote.model} default).</li>
 * </ul>
 *
 * <p>{@code IB2B} auth fetches a JWT through {@link Ib2bTokenClient} and sends
 * it as {@code X-zzzz-E2E-Trust-Token}, plus a per-request UUID
 * {@code X-zzzz-Request-Correlation-Id}. No proxy is configured: the remote LLM
 * is reachable directly from the bank intranet.
 */
@Component
public class RemoteApiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteApiLlmProvider.class);
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String DEFAULT_MODEL = "gpt-5.4";

    private final RemoteProperties config;
    private final Map<String, RemoteModelProperties> modelsByName;
    private final String defaultModel;
    private final int defaultMaxTokens;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final Ib2bTokenClient ib2bClient;

    public RemoteApiLlmProvider(
            Chat2PayProperties properties,
            ObjectMapper mapper,
            Ib2bTokenClient ib2bClient) {
        this.config = properties.remote();
        this.modelsByName = indexModels(config);
        this.defaultModel = resolveDefaultModel(config);
        this.defaultMaxTokens = config == null
                ? DEFAULT_MAX_TOKENS
                : config.maxCompletionTokensOr(DEFAULT_MAX_TOKENS);
        this.mapper = mapper;
        this.ib2bClient = ib2bClient;
        this.http = RestClient.builder().build();
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.REMOTE_API;
    }

    @Override
    public boolean isAvailable() {
        // Either a model registry entry (with a usable url + working auth) or
        // the legacy single-model fallback (base-url + api-key) makes the
        // provider available.
        if (config == null) return false;
        if (legacyAvailable(config)) return true;
        for (RemoteModelProperties m : modelsByName.values()) {
            if (modelEntryAvailable(m)) return true;
        }
        return false;
    }

    @Override
    public LlmCompletionResponse complete(LlmCompletionRequest request) {
        if (config == null) {
            throw new IllegalStateException("Remote LLM provider is not configured.");
        }
        String requestedModel = nullIfBlank(request.model());
        if (requestedModel == null) requestedModel = defaultModel;

        Endpoint endpoint = resolveEndpoint(requestedModel);
        if (endpoint == null) {
            throw new IllegalStateException(
                    "Remote LLM provider is not configured for model '" + requestedModel
                            + "'. Configure chat2pay.remote.models[] or the legacy "
                            + "chat2pay.remote.{base-url,api-key}.");
        }

        return sendCompletion(request, endpoint, requestedModel, false);
    }

    private LlmCompletionResponse sendCompletion(LlmCompletionRequest request,
                                                 Endpoint endpoint,
                                                 String model,
                                                 boolean isRetry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        int maxTokens = request.maxTokens() != null
                ? request.maxTokens()
                : endpoint.maxTokensOr(defaultMaxTokens);
        body.put("max_completion_tokens", maxTokens);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("messages", ChatCompletionPayloads.messages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", ChatCompletionPayloads.tools(request.tools()));
        }
        if (request.toolChoice() != null && !request.toolChoice().isBlank()) {
            body.put("tool_choice", request.toolChoice());
        }
        if (endpoint.user() != null) body.put("user", endpoint.user());

        String correlationId = Ib2bTokenClient.newCorrelationId();
        log.debug("LLM Remote request: url={} provider={} model={} auth={} correlationId={} body={}",
                endpoint.url(), providerType(), model, endpoint.auth(), correlationId, toJson(body));
        String rawResponse;
        try {
            rawResponse = http.post()
                    .uri(endpoint.url())
                    .headers(h -> applyHeaders(h, endpoint, correlationId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException ex) {
            if (!isRetry && endpoint.auth() == AuthMode.IB2B && ex.getStatusCode().value() == 401) {
                log.debug("LLM Remote 401 with IB2B token; refreshing and retrying once.");
                ib2bClient.refresh();
                return sendCompletion(request, endpoint, model, true);
            }
            log.debug("LLM Remote error response: url={} provider={} model={} status={} headers={} body={}",
                    endpoint.url(), providerType(), model, ex.getStatusCode().value(),
                    ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw ex;
        } catch (RestClientResponseException ex) {
            log.debug("LLM Remote error response: url={} provider={} model={} status={} headers={} body={}",
                    endpoint.url(), providerType(), model, ex.getStatusCode().value(),
                    ex.getResponseHeaders(), ex.getResponseBodyAsString());
            throw ex;
        }
        log.debug("LLM Remote response: url={} provider={} model={} body={}",
                endpoint.url(), providerType(), model, rawResponse);

        ChatCompletionResponse resp = readJson(rawResponse, ChatCompletionResponse.class);

        Choice choice = resp != null && resp.choices() != null && !resp.choices().isEmpty()
                ? resp.choices().get(0)
                : null;
        ChoiceMessage message = choice == null ? null : choice.message();
        List<ToolCall> toolCalls = message != null && message.toolCalls() != null
                ? message.toolCalls().stream()
                    .filter(tc -> tc.function() != null && tc.function().name() != null)
                    .map(tc -> new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()))
                    .toList()
                : List.of();
        String content = extractText(message == null ? null : message.content());
        String reasoningContent = extractText(message == null ? null : message.reasoningContent());
        String finishReason = choice == null ? null : choice.finishReason();
        log.debug("LLM Remote parsed response: model={} finishReason={} contentChars={} reasoningChars={} toolCalls={} toolCallDetails={}",
                model, finishReason, content == null ? 0 : content.length(),
                reasoningContent == null ? 0 : reasoningContent.length(), toolCalls.size(), toolCalls);
        return new LlmCompletionResponse(
                LlmProviderType.REMOTE_API,
                model,
                content,
                toolCalls,
                finishReason,
                reasoningContent
        );
    }

    private void applyHeaders(HttpHeaders h, Endpoint endpoint, String correlationId) {
        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        h.set("X-zzzz-Request-Correlation-Id", correlationId);
        switch (endpoint.auth()) {
            case BEARER -> {
                if (endpoint.apiKey() != null) h.setBearerAuth(endpoint.apiKey());
            }
            case IB2B -> {
                if (!ib2bClient.isConfigured()) {
                    throw new IllegalStateException(
                            "Model '" + endpoint.modelName() + "' requires IB2B auth but "
                                    + "chat2pay.remote.ib2b.{token-url,username,password} is not configured.");
                }
                h.set("X-zzzz-E2E-Trust-Token", ib2bClient.currentToken());
            }
        }
    }

    // ---- endpoint resolution -------------------------------------------------

    private Endpoint resolveEndpoint(String modelName) {
        RemoteModelProperties entry = modelsByName.get(modelName.toLowerCase(Locale.ROOT));
        if (entry != null) {
            return modelEndpoint(entry);
        }
        // Legacy single-model fallback: any model name routes through the
        // global base-url with Bearer auth, mirroring v1 behavior.
        if (legacyAvailable(config)) {
            String url = trimTrailingSlash(config.baseUrl()) + "/chat/completions";
            return new Endpoint(
                    modelName,
                    url,
                    AuthMode.BEARER,
                    config.apiKey(),
                    null,
                    config.maxCompletionTokens()
            );
        }
        return null;
    }

    private Endpoint modelEndpoint(RemoteModelProperties entry) {
        String modelName = nullIfBlank(entry.name());
        String url = nullIfBlank(entry.url());
        if (url == null) {
            throw new IllegalStateException(
                    "Remote LLM model '" + modelName + "' is missing chat2pay.remote.models[].url.");
        }

        AuthMode auth = parseAuth(entry.auth());
        String apiKey = auth == AuthMode.BEARER ? resolveBearerApiKey(entry) : null;
        if (auth == AuthMode.BEARER && apiKey == null) {
            throw new IllegalStateException(
                    "Remote LLM model '" + modelName + "' requires Bearer auth but no api key is configured. "
                            + "Set chat2pay.remote.models[].api-key or chat2pay.remote.api-key.");
        }
        if (auth == AuthMode.IB2B && !ib2bClient.isConfigured()) {
            throw new IllegalStateException(
                    "Remote LLM model '" + modelName + "' requires IB2B auth but "
                            + "chat2pay.remote.ib2b.{token-url,username,password} is not configured.");
        }
        return new Endpoint(
                modelName,
                url,
                auth,
                apiKey,
                nullIfBlank(entry.user()),
                entry.maxCompletionTokens()
        );
    }

    private static Map<String, RemoteModelProperties> indexModels(RemoteProperties config) {
        if (config == null || config.models() == null || config.models().isEmpty()) return Map.of();
        Map<String, RemoteModelProperties> out = new HashMap<>();
        for (RemoteModelProperties m : config.models()) {
            if (m == null || nullIfBlank(m.name()) == null) continue;
            out.put(m.name().toLowerCase(Locale.ROOT), m);
        }
        return Map.copyOf(out);
    }

    private static String resolveDefaultModel(RemoteProperties config) {
        if (config == null) return DEFAULT_MODEL;
        String configured = nullIfBlank(config.model());
        if (configured != null) return configured;
        if (config.models() != null && !config.models().isEmpty()) {
            for (RemoteModelProperties m : config.models()) {
                if (m != null && nullIfBlank(m.name()) != null) return m.name();
            }
        }
        return DEFAULT_MODEL;
    }

    private boolean modelEntryAvailable(RemoteModelProperties m) {
        if (m == null || nullIfBlank(m.url()) == null) return false;
        AuthMode auth = parseAuth(m.auth());
        return switch (auth) {
            case BEARER -> resolveBearerApiKey(m) != null;
            case IB2B -> ib2bClient.isConfigured();
        };
    }

    private String resolveBearerApiKey(RemoteModelProperties entry) {
        String modelKey = entry == null ? null : nullIfBlank(entry.apiKey());
        if (modelKey != null) return modelKey;
        return config == null ? null : nullIfBlank(config.apiKey());
    }

    private static boolean legacyAvailable(RemoteProperties config) {
        return config != null
                && nullIfBlank(config.baseUrl()) != null
                && nullIfBlank(config.apiKey()) != null;
    }

    private static AuthMode parseAuth(String raw) {
        if (raw == null || raw.isBlank()) return AuthMode.BEARER;
        try {
            return AuthMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return AuthMode.BEARER;
        }
    }

    // ---- helpers -------------------------------------------------------------

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

    private static String trimTrailingSlash(String value) {
        if (value == null) return null;
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    enum AuthMode { BEARER, IB2B }

    record Endpoint(
            String modelName,
            String url,
            AuthMode auth,
            String apiKey,
            String user,
            Integer maxCompletionTokens
    ) {
        int maxTokensOr(int defaultValue) {
            return maxCompletionTokens == null ? defaultValue : maxCompletionTokens;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonAlias("finish_reason") String finishReason, ChoiceMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceMessage(
            String role,
            Object content,
            @JsonAlias("reasoning_content") Object reasoningContent,
            @JsonAlias("tool_calls") List<RawToolCall> toolCalls
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawToolCall(String id, RawFunction function) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawFunction(String name, String arguments) {}
}
