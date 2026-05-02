package com.chat2pay.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "chat2pay")
public record Chat2PayProperties(
        String primaryProvider,
        String fallbackProvider,
        String defaultCurrency,
        IntentProperties intent,
        DownstreamProperties downstream,
        RemoteProperties remote,
        Map<String, UseCaseProperties> useCases
) {
    public boolean useLlmIntent() {
        return intent == null || intent.useLlm() == null || intent.useLlm();
    }

    public int intentMaxTokens() {
        return intent == null || intent.maxTokens() == null ? 350 : intent.maxTokens();
    }

    public double intentTemperature() {
        return intent == null || intent.temperature() == null ? 0.0 : intent.temperature();
    }

    public boolean downstreamMockEnabled() {
        return downstream == null || downstream.mockEnabled() == null || downstream.mockEnabled();
    }

    public int downstreamRequestTimeoutMs() {
        return downstream == null || downstream.requestTimeoutMs() == null
                ? 30000 : downstream.requestTimeoutMs();
    }

    public int downstreamPayeeCacheTtlSeconds() {
        return downstream == null || downstream.payeeCacheTtlSeconds() == null
                ? 60 : downstream.payeeCacheTtlSeconds();
    }

    public String defaultCurrencyOrHkd() {
        return defaultCurrency == null || defaultCurrency.isBlank() ? "HKD" : defaultCurrency;
    }

    public record IntentProperties(
            Boolean useLlm,
            Integer maxTokens,
            Double temperature
    ) {}

    /**
     * Per-use-case overrides for which provider/model to call. Keys are
     * case-insensitive use-case names (see {@code LlmUseCase}, e.g. "title",
     * "intent", "chat"). Missing keys fall back to the global
     * {@code primary-provider} / {@code fallback-provider} routing.
     */
    public record UseCaseProperties(String provider, String model) {}

    /**
     * Remote LLM backend. Two configuration shapes are supported:
     *  - Legacy single-model: populate {@code base-url}, {@code api-key},
     *    {@code model}, {@code max-completion-tokens} (matches the v1 setup).
     *  - Multi-model registry: populate {@code models[]}; the entry whose
     *    {@code name} matches the request's model is used. {@code auth=IB2B}
     *    routes through the corporate token translator (see {@code ib2b}).
     * Both can coexist: the legacy fields act as the default when no model
     * matches.
     */
    public record RemoteProperties(
            String baseUrl,
            String apiKey,
            String model,
            Integer maxCompletionTokens,
            Ib2bProperties ib2b,
            List<RemoteModelProperties> models
    ) {
        public int maxCompletionTokensOr(int defaultValue) {
            return maxCompletionTokens == null ? defaultValue : maxCompletionTokens;
        }
    }

    public record Ib2bProperties(
            String tokenUrl,
            String username,
            String password,
            Integer tokenTtlSeconds
    ) {
        public int tokenTtlSecondsOr(int defaultValue) {
            return tokenTtlSeconds == null || tokenTtlSeconds <= 0
                    ? defaultValue : tokenTtlSeconds;
        }
    }

    /**
     * One entry in the remote-LLM model registry.
     * <ul>
     *   <li>{@code url}: full chat-completions endpoint for this model.</li>
     *   <li>{@code auth}: {@code BEARER} (default, uses {@code apiKey}) or
     *       {@code IB2B} (uses the IB2B token translator).</li>
     *   <li>{@code user}: optional OpenAI-style {@code user} field included in
     *       the request body (some internal endpoints expect it).</li>
     * </ul>
     */
    public record RemoteModelProperties(
            String name,
            String url,
            String auth,
            String apiKey,
            String user,
            Integer maxCompletionTokens
    ) {}

    public record DownstreamProperties(
            Boolean mockEnabled,
            String loginUrlTemplate,
            String payeeUrl,
            String confirmUrl,
            Integer requestTimeoutMs,
            Integer payeeCacheTtlSeconds,
            String channelId,
            String countryCode,
            String groupMember,
            String locale,
            String sourceSystemId,
            String deviceId,
            String userAgent
    ) {}
}
