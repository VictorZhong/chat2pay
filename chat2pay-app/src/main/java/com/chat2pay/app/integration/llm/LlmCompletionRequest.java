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

    public record Message(
            Role role,
            String content,
            String name,
            String toolCallId,
            List<ToolCall> toolCalls
    ) {
        public Message(Role role, String content) {
            this(role, content, null, null, List.of());
        }

        public static Message assistantToolCalls(List<ToolCall> toolCalls) {
            return new Message(Role.ASSISTANT, "", null, null,
                    toolCalls == null ? List.of() : toolCalls);
        }

        public static Message toolResult(String toolCallId, String name, String content) {
            return new Message(Role.TOOL, content, name, toolCallId, List.of());
        }
    }

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}

    public record ToolCall(String id, String name, String arguments) {}
}
