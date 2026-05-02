package com.chat2pay.app.integration.llm;

import com.chat2pay.app.domain.conversation.LlmProviderType;

import java.util.List;

public record LlmCompletionResponse(
        LlmProviderType provider,
        String model,
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        String reasoningContent
) {
    public LlmCompletionResponse(LlmProviderType provider, String model, String content) {
        this(provider, model, content, List.of(), null, null);
    }

    public LlmCompletionResponse(LlmProviderType provider, String model, String content, List<ToolCall> toolCalls) {
        this(provider, model, content, toolCalls, null, null);
    }

    public record ToolCall(String id, String name, String arguments) {}
}
