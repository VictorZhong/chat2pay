package com.chat2pay.app.integration.llm;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic chat completion request used by the intent/tool decision
 * parser and future full tool-calling providers.
 *
 * <p>{@code model} is an optional per-call override. When non-null the provider
 * will use it instead of its configured default model — for the remote
 * provider this also picks the matching entry in
 * {@code chat2pay.remote.models[]} (URL + auth mode). When null, the provider
 * falls back to its globally configured model.
 */
public record LlmCompletionRequest(
        List<Message> messages,
        Integer maxTokens,
        Double temperature,
        List<ToolDefinition> tools,
        String toolChoice,
        String model
) {
    public LlmCompletionRequest(List<Message> messages, Integer maxTokens, Double temperature) {
        this(messages, maxTokens, temperature, List.of(), null, null);
    }

    public LlmCompletionRequest(
            List<Message> messages,
            Integer maxTokens,
            Double temperature,
            List<ToolDefinition> tools,
            String toolChoice
    ) {
        this(messages, maxTokens, temperature, tools, toolChoice, null);
    }

    public LlmCompletionRequest withModel(String overrideModel) {
        if (overrideModel == null || overrideModel.isBlank()) return this;
        return new LlmCompletionRequest(messages, maxTokens, temperature, tools, toolChoice, overrideModel);
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
