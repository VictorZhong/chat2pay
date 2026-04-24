package com.chat2pay.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat2pay")
public record Chat2PayProperties(
        String primaryProvider,
        String fallbackProvider,
        String defaultCurrency
) {
}
