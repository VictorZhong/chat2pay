package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;

public record LlmCompletion(String content, LlmProviderType provider, String model) {
}
