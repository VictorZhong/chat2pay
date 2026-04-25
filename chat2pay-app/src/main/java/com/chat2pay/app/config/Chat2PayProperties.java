package com.chat2pay.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat2pay")
public record Chat2PayProperties(
        String primaryProvider,
        String fallbackProvider,
        String defaultCurrency,
        IntentProperties intent
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

    public record IntentProperties(
            Boolean useLlm,
            Integer maxTokens,
            Double temperature
    ) {}
}
