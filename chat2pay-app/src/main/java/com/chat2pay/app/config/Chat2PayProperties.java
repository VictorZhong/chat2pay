package com.chat2pay.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat2pay")
public record Chat2PayProperties(
        String primaryProvider,
        String fallbackProvider,
        String defaultCurrency,
        IntentProperties intent,
        DownstreamProperties downstream
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
