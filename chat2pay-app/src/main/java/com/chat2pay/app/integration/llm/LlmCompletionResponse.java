package com.chat2pay.app.integration.llm;

import com.chat2pay.app.domain.conversation.LlmProviderType;

public record LlmCompletionResponse(
        LlmProviderType provider,
        String model,
        String content
) {}
