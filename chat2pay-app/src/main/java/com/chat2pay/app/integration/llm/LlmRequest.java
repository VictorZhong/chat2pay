package com.chat2pay.app.integration.llm;

public record LlmRequest(String prompt, Integer maxTokens) {
}
