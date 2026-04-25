package com.chat2pay.app.integration.llm.copilot;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.ChatCompletionPayloads;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.persistence.repository.LlmCredentialStore;
import com.chat2pay.app.persistence.repository.LlmCredentialStore.Credential;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot Personal LLM provider. Mirrors the Python reference at
 * docs/99-ref.md:
 *   1. Read api_key (and any cached session_token) from ctp_llm_credential.
 *   2. If session_token is missing/expired, exchange api_key against
 *      https://api.github.com/copilot_internal/v2/token (this call is what
 *      requires the corporate proxy in production).
 *   3. POST /chat/completions with the Copilot-specific headers.
 * Operators rotate the api_key via plain SQL.
 */
@Component
public class CopilotPersonalLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(CopilotPersonalLlmProvider.class);

    private static final String API_VERSION = "2025-04-01";
    private static final String EDITOR_PLUGIN_VERSION = "copilot.vim/1.16.0";
    private static final String USER_AGENT = "GithubCopilot/1.155.0";
    /** Copilot session tokens are short-lived (~30 min); we conservatively assume 25. */
    private static final Duration ASSUMED_SESSION_TTL = Duration.ofMinutes(25);

    private final LlmCredentialStore credentials;
    private final String bootstrapApiKey;
    private final String bootstrapSessionToken;
    private final String configuredModel;
    private final int maxCompletionTokens;
    private final String accountType;
    private final String editorVersion;
    private final String configuredBaseUrl;
    private final String tokenUrl;

    private final ProxyConfig proxy;

    private final RestClient http;

    public CopilotPersonalLlmProvider(
            LlmCredentialStore credentials,
            @Value("${chat2pay.copilot.bootstrap-api-key:}") String bootstrapApiKey,
            @Value("${chat2pay.copilot.bootstrap-session-token:}") String bootstrapSessionToken,
            @Value("${chat2pay.copilot.model:gpt-5.4}") String configuredModel,
            @Value("${chat2pay.copilot.max-completion-tokens:2048}") int maxCompletionTokens,
            @Value("${chat2pay.copilot.account-type:individual}") String accountType,
            @Value("${chat2pay.copilot.editor-version:1.114.0}") String editorVersion,
            @Value("${chat2pay.copilot.base-url:}") String configuredBaseUrl,
            @Value("${chat2pay.copilot.token-url:https://api.github.com/copilot_internal/v2/token}") String tokenUrl,
            @Value("${chat2pay.copilot.proxy-url:}") String proxyUrl) {
        this.credentials = credentials;
        this.bootstrapApiKey = nullIfBlank(bootstrapApiKey);
        this.bootstrapSessionToken = nullIfBlank(bootstrapSessionToken);
        this.configuredModel = nullIfBlank(configuredModel);
        this.maxCompletionTokens = maxCompletionTokens;
        this.accountType = accountType;
        this.editorVersion = editorVersion;
        this.configuredBaseUrl = nullIfBlank(configuredBaseUrl);
        this.tokenUrl = nullIfBlank(tokenUrl) == null
                ? "https://api.github.com/copilot_internal/v2/token" : tokenUrl;
        this.proxy = resolveProxyConfig(proxyUrl);

        this.http = RestClient.builder()
                .requestFactory(buildRequestFactory())
                .build();
    }

    @PostConstruct
    public void bootstrap() {
        // Persist env-supplied creds the very first time the app starts; subsequent
        // rotations are pure SQL.
        if (bootstrapApiKey != null || bootstrapSessionToken != null) {
            credentials.bootstrapIfMissing(LlmProviderType.COPILOT_PERSONAL,
                    bootstrapApiKey, bootstrapSessionToken);
        }
        Credential c = credentials.find(LlmProviderType.COPILOT_PERSONAL).orElse(null);
        if (c == null) {
            log.info("Copilot Personal: no credential row in ctp_llm_credential — provider unavailable until configured.");
        } else if (!c.hasApiKey() && (c.sessionToken() == null || c.sessionToken().isBlank())) {
            log.info("Copilot Personal: credential row exists but has no api_key/session_token.");
        } else {
            log.info("Copilot Personal: credentials loaded from ctp_llm_credential.");
        }
        if (proxy.enabled()) {
            log.info("Copilot Personal: outbound proxy configured at {}://{}:{} auth={}",
                    proxy.scheme(), proxy.host(), proxy.port(), proxy.hasCredentials());
        }
    }

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.COPILOT_PERSONAL;
    }

    @Override
    public boolean isAvailable() {
        return credentials.find(LlmProviderType.COPILOT_PERSONAL)
                .map(c -> c.hasApiKey() || c.hasFreshSessionToken(Instant.now()))
                .orElse(false);
    }

    @Override
    public LlmCompletionResponse complete(LlmCompletionRequest request) {
        String model = configuredModel == null ? "gpt-5.4" : configuredModel;
        try {
            String token = ensureSessionToken();
            return sendCompletion(request, token, model);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 401) {
                credentials.upsertSessionToken(LlmProviderType.COPILOT_PERSONAL, null, null);
                try {
                    return sendCompletion(request, ensureSessionToken(), model);
                } catch (HttpClientErrorException retryEx) {
                    throw new RestClientException("Copilot chat completions failed: "
                            + retryEx.getStatusCode(), retryEx);
                }
            }
            throw new RestClientException("Copilot chat completions failed: " + ex.getStatusCode(), ex);
        }
    }

    private LlmCompletionResponse sendCompletion(LlmCompletionRequest request, String token, String model) {
        log.debug("Copilot completion request: model={} messages={} tools={} toolChoice={} baseUrl={}",
                model,
                request.messages() == null ? 0 : request.messages().size(),
                request.tools() == null ? 0 : request.tools().size(),
                request.toolChoice(),
                baseUrl());
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

        ChatCompletionResponse resp = http.post()
                .uri(baseUrl() + "/chat/completions")
                .headers(h -> copilotHeaders(h, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);

        Object rawContent = resp != null && resp.choices() != null && !resp.choices().isEmpty()
                && resp.choices().get(0).message() != null
                ? resp.choices().get(0).message().content() : null;
        List<ToolCall> toolCalls = resp != null && resp.choices() != null && !resp.choices().isEmpty()
                && resp.choices().get(0).message() != null
                && resp.choices().get(0).message().toolCalls() != null
                ? resp.choices().get(0).message().toolCalls().stream()
                    .filter(tc -> tc.function() != null && tc.function().name() != null)
                    .map(tc -> new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()))
                    .toList()
                : List.of();
        String content = extractText(rawContent);
        log.debug("Copilot completion response: model={} contentChars={} toolCalls={}",
                model, content == null ? 0 : content.length(), toolCalls.size());
        return new LlmCompletionResponse(LlmProviderType.COPILOT_PERSONAL, model, content, toolCalls);
    }

    // ---- token exchange ------------------------------------------------------

    private String ensureSessionToken() {
        Credential c = credentials.find(LlmProviderType.COPILOT_PERSONAL)
                .orElseThrow(() -> new IllegalStateException(
                        "Copilot Personal provider is not configured (insert a row into ctp_llm_credential)."));
        Instant now = Instant.now();
        if (c.hasFreshSessionToken(now)) return c.sessionToken();
        if (!c.hasApiKey()) {
            throw new IllegalStateException(
                    "Cannot refresh Copilot session token: ctp_llm_credential.api_key is empty.");
        }
        log.debug("Refreshing Copilot session token: url={} proxyEnabled={} proxyAuth={}",
                tokenUrl, proxy.enabled(), proxy.hasCredentials());
        TokenResponse token;
        try {
            token = http.get()
                    .uri(tokenUrl)
                    .headers(h -> githubHeaders(h, c.apiKey()))
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw new RestClientException(
                        "Copilot token refresh failed with 401 Unauthorized. "
                                + "Check ctp_llm_credential.api_key; Copilot session tokens are refreshed "
                                + "automatically, but the stored api_key must still be valid.", ex);
            }
            if (ex.getStatusCode().value() == 407) {
                throw new RestClientException(
                        "Copilot token refresh failed with 407 Proxy Authentication Required. "
                                + "Check LLM_PROXY_URL. It must include proxy credentials as "
                                + "http://username:password@host:port, with special characters percent-encoded.", ex);
            }
            throw ex;
        } catch (ResourceAccessException ex) {
            if (isAuthenticationRetryFailure(ex)) {
                throw new RestClientException(
                        "Copilot token refresh failed because HTTP authentication was rejected repeatedly. "
                                + "This usually means the proxy credentials are wrong, the proxy URL/user/password "
                                + "is not URL-encoded correctly, or the GitHub api_key in ctp_llm_credential "
                                + "is invalid.", ex);
            }
            throw ex;
        }
        if (token == null || token.token() == null || token.token().isBlank()) {
            throw new RestClientException("GitHub Copilot token response did not include a token.");
        }
        Instant expiresAt = token.expiresAt() != null
                ? Instant.ofEpochSecond(token.expiresAt())
                : Instant.now().plus(ASSUMED_SESSION_TTL);
        credentials.upsertSessionToken(LlmProviderType.COPILOT_PERSONAL, token.token(), expiresAt);
        log.debug("Copilot session token refreshed; expiresAt={}", expiresAt);
        return token.token();
    }

    // ---- headers --------------------------------------------------------------

    private void copilotHeaders(HttpHeaders h, String sessionToken) {
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken);
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

    private void githubHeaders(HttpHeaders h, String apiKey) {
        h.set(HttpHeaders.AUTHORIZATION, "token " + apiKey);
        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        h.set("editor-version", "vscode/" + editorVersion);
        h.set("editor-plugin-version", EDITOR_PLUGIN_VERSION);
        h.set(HttpHeaders.USER_AGENT, USER_AGENT);
        h.set("x-github-api-version", API_VERSION);
        h.set("x-vscode-user-agent-library-version", "electron-fetch");
    }

    // ---- HTTP client construction (with optional corporate proxy) -------------

    private HttpComponentsClientHttpRequestFactory buildRequestFactory() {
        CloseableHttpClient client = buildHttpClient();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(client);
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setConnectionRequestTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(60))
                .setProxyPreferredAuthSchemes(List.of(StandardAuthScheme.BASIC))
                .build();

        var builder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries();

        if (!proxy.enabled()) return builder.build();

        HttpHost proxyHost = new HttpHost(proxy.scheme(), proxy.host(), proxy.port());
        builder.setProxy(proxyHost);
        if (proxy.hasCredentials()) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    new AuthScope(proxyHost),
                    new UsernamePasswordCredentials(proxy.username(), proxy.password().toCharArray())
            );
            builder.setDefaultCredentialsProvider(credentialsProvider);
        }
        return builder.build();
    }

    private String baseUrl() {
        if (configuredBaseUrl != null) return configuredBaseUrl;
        return "individual".equalsIgnoreCase(accountType)
                ? "https://api.githubcopilot.com"
                : "https://api." + accountType + ".githubcopilot.com";
    }

    private ProxyConfig resolveProxyConfig(String proxyUrl) {
        String url = nullIfBlank(proxyUrl);
        if (url == null) return new ProxyConfig("http", null, 0, null, null);

        URI uri = URI.create(url);
        String scheme = nullIfBlank(uri.getScheme()) == null ? "http" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultProxyPort(scheme);
        if (host == null || port <= 0) {
            throw new IllegalArgumentException(
                    "Invalid LLM_PROXY_URL. Expected http://username:password@host:port.");
        }
        String username = null;
        String password = null;
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null && !rawUserInfo.isBlank()) {
            String[] parts = rawUserInfo.split(":", 2);
            username = percentDecode(parts[0]);
            password = parts.length > 1 ? percentDecode(parts[1]) : "";
        }
        return new ProxyConfig(scheme, host, port, username, password);
    }

    private static int defaultProxyPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String percentDecode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean isAuthenticationRetryFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("too many authentication attempts")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    // ---- response shapes ------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String token, @JsonAlias("expires_at") Long expiresAt) {}

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

    record ProxyConfig(String scheme, String host, int port, String username, String password) {
        boolean enabled() {
            return host != null && !host.isBlank() && port > 0;
        }

        boolean hasCredentials() {
            return username != null && !username.isBlank() && password != null;
        }
    }
}
