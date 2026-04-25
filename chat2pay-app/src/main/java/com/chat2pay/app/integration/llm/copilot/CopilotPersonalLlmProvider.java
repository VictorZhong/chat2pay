package com.chat2pay.app.integration.llm.copilot;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GitHub Copilot Personal LLM provider. Mirrors the behavior of the Python
 * reference at docs/99-ref.md — performs a one-shot session-token exchange
 * (or accepts COPILOT_SESSION_TOKEN), then calls /chat/completions in
 * non-streaming mode. The orchestrator remains authoritative for the
 * structured-block contract; this provider exists so a future LLM-driven
 * branch (intent extraction, free-form copy) has a wired-up surface.
 */
@Component
public class CopilotPersonalLlmProvider implements LlmProvider {

    private static final String API_VERSION = "2025-04-01";
    private static final String EDITOR_PLUGIN_VERSION = "copilot.vim/1.16.0";
    private static final String USER_AGENT = "GithubCopilot/1.155.0";

    private final String apiKey;
    private final String preconfiguredSessionToken;
    private final String configuredModel;
    private final String accountType;
    private final String editorVersion;
    private final String configuredBaseUrl;
    private final RestClient http = RestClient.builder().build();
    private final AtomicReference<String> sessionToken = new AtomicReference<>();

    public CopilotPersonalLlmProvider(
            @Value("${chat2pay.copilot.api-key:${LLM_API_KEY:}}") String apiKey,
            @Value("${chat2pay.copilot.session-token:${COPILOT_SESSION_TOKEN:}}") String preconfiguredSessionToken,
            @Value("${chat2pay.copilot.model:${LLM_MODEL:gpt-4o}}") String configuredModel,
            @Value("${chat2pay.copilot.account-type:${GITHUB_COPILOT_ACCOUNT_TYPE:individual}}") String accountType,
            @Value("${chat2pay.copilot.editor-version:${COPILOT_EDITOR_VERSION:1.114.0}}") String editorVersion,
            @Value("${chat2pay.copilot.base-url:${LLM_BASE_URL:}}") String configuredBaseUrl) {
        this.apiKey = nullIfBlank(apiKey);
        this.preconfiguredSessionToken = nullIfBlank(preconfiguredSessionToken);
        this.configuredModel = configuredModel;
        this.accountType = accountType;
        this.editorVersion = editorVersion;
        this.configuredBaseUrl = nullIfBlank(configuredBaseUrl);
        if (this.preconfiguredSessionToken != null) {
            sessionToken.set(this.preconfiguredSessionToken);
        }
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.COPILOT_PERSONAL;
    }

    @Override
    public boolean isAvailable() {
        return preconfiguredSessionToken != null || apiKey != null;
    }

    @Override
    public LlmCompletionResponse complete(LlmCompletionRequest request) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Copilot Personal provider is not configured (set LLM_API_KEY or COPILOT_SESSION_TOKEN).");
        }
        ensureSessionToken();

        Map<String, Object> body = new HashMap<>();
        body.put("model", configuredModel);
        body.put("stream", false);
        if (request.maxTokens() != null) body.put("max_completion_tokens", request.maxTokens());
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("messages", request.messages().stream()
                .map(m -> Map.of("role", m.role().name().toLowerCase(Locale.ROOT), "content", m.content()))
                .toList());

        try {
            ChatCompletionResponse resp = http.post()
                    .uri(baseUrl() + "/chat/completions")
                    .headers(this::copilotHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            String content = resp != null && resp.choices() != null && !resp.choices().isEmpty()
                    && resp.choices().get(0).message() != null
                    ? resp.choices().get(0).message().content() : "";
            return new LlmCompletionResponse(LlmProviderType.COPILOT_PERSONAL, configuredModel, content);
        } catch (HttpClientErrorException ex) {
            // Token may have expired; clear and surface for caller to retry.
            if (preconfiguredSessionToken == null) sessionToken.set(null);
            throw new RestClientException("Copilot chat completions failed: " + ex.getStatusCode(), ex);
        }
    }

    // ---- helpers --------------------------------------------------------------

    private void ensureSessionToken() {
        if (sessionToken.get() != null) return;
        if (apiKey == null) {
            throw new IllegalStateException("Cannot exchange Copilot session token without LLM_API_KEY.");
        }
        TokenResponse resp = http.get()
                .uri("https://api.github.com/copilot_internal/v2/token")
                .headers(this::githubHeaders)
                .retrieve()
                .body(TokenResponse.class);
        if (resp == null || resp.token() == null || resp.token().isBlank()) {
            throw new RestClientException("GitHub Copilot token response did not include a token.");
        }
        sessionToken.set(resp.token());
    }

    private void copilotHeaders(HttpHeaders h) {
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken.get());
        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        h.set("copilot-integration-id", "vscode-chat");
        h.set("editor-version", "vscode/" + editorVersion);
        h.set("editor-plugin-version", EDITOR_PLUGIN_VERSION);
        h.set(HttpHeaders.USER_AGENT, USER_AGENT);
        h.set("openai-intent", "conversation-panel");
        h.set("x-github-api-version", API_VERSION);
        h.set("x-vscode-user-agent-library-version", "electron-fetch");
        h.set("x-initiator", "agent");
    }

    private void githubHeaders(HttpHeaders h) {
        h.set(HttpHeaders.AUTHORIZATION, "token " + apiKey);
        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        h.set("editor-version", "vscode/" + editorVersion);
        h.set("editor-plugin-version", EDITOR_PLUGIN_VERSION);
        h.set(HttpHeaders.USER_AGENT, USER_AGENT);
        h.set("x-github-api-version", API_VERSION);
        h.set("x-vscode-user-agent-library-version", "electron-fetch");
    }

    private String baseUrl() {
        if (configuredBaseUrl != null) return configuredBaseUrl;
        return "individual".equalsIgnoreCase(accountType)
                ? "https://api.githubcopilot.com"
                : "https://api." + accountType + ".githubcopilot.com";
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---- response shapes ------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String token, Long expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(ChoiceMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceMessage(String role, String content) {}
}
