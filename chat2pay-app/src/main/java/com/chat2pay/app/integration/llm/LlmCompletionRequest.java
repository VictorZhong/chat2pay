package com.chat2pay.app.integration.llm;

import java.util.List;

/**
 * Provider-agnostic chat completion request used by the intent/tool decision
 * parser and future full tool-calling providers.
 */
public record LlmCompletionRequest(
        List<Message> messages,
        Integer maxTokens,
        Double temperature
) {
    public record Message(Role role, String content) {}
    public enum Role { SYSTEM, USER, ASSISTANT }
}
