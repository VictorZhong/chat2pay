package com.chat2pay.app.integration.llm;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic chat completion request used by the intent/tool decision
 * parser and future full tool-calling providers.
 */
public record LlmCompletionRequest(
        List<Message> messages,
        Integer maxTokens,
        Double temperature,
        List<ToolDefinition> tools,
        String toolChoice
) {
    public LlmCompletionRequest(List<Message> messages, Integer maxTokens, Double temperature) {
        this(messages, maxTokens, temperature, List.of(), null);
    }

    public record Message(Role role, String content) {}
    public enum Role { SYSTEM, USER, ASSISTANT }

    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}
}
