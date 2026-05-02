package com.chat2pay.app.integration.llm;

/**
 * Configurable LLM call sites. Each enum value can be overridden in
 * {@code chat2pay.use-cases.<key>.{provider,model}} to point at a different
 * provider/model than the global router default — e.g. cheap local model for
 * title generation, full Copilot for the main chat tool loop.
 */
public enum LlmUseCase {
    /** Main chat tool-calling loop in {@code ChatOrchestratorService}. */
    CHAT("chat"),
    /** Single-shot intent + slot extraction in {@code IntentInterpreter}. */
    INTENT("intent"),
    /** Best-effort short title summarization in {@code SessionTitleSuggester}. */
    TITLE("title");

    private final String key;

    LlmUseCase(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
