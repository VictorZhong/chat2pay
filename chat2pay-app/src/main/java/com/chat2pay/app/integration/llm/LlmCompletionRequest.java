package com.chat2pay.app.integration.llm;

import java.util.List;

/**
 * Provider-agnostic chat completion request. V1 uses plain-text turns only;
 * tool calling and structured output stay inside the deterministic orchestrator.
 */
public record LlmCompletionRequest(
        List<Message> messages,
        Integer maxTokens,
        Double temperature
) {
    public record Message(Role role, String content) {}
    public enum Role { SYSTEM, USER, ASSISTANT }
}
