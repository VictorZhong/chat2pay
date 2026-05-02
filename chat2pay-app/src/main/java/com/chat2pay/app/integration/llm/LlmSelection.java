package com.chat2pay.app.integration.llm;

/**
 * Result of {@link LlmRouter#select(LlmUseCase)} — the provider to call, plus
 * an optional model override that the caller should propagate into the
 * completion request. {@code modelOverride == null} means "use the provider's
 * configured default model".
 */
public record LlmSelection(LlmProvider provider, String modelOverride) {}
