package com.chat2pay.app.integration.llm;

import com.chat2pay.app.domain.conversation.LlmProviderType;

public interface LlmProvider {

    LlmProviderType providerType();

    /**
     * Returns true when the provider is reachable / has credentials wired.
     * Used by the router to fall back when a primary provider is misconfigured.
     */
    default boolean isAvailable() {
        return false;
    }

    /**
     * Plain-text completion. Implementations that have not been wired up yet
     * should throw {@link UnsupportedOperationException}.
     */
    default LlmCompletionResponse complete(LlmCompletionRequest request) {
        throw new UnsupportedOperationException(providerType() + " provider is not wired");
    }
}
