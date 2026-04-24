package com.chat2pay.app.integration.llm;

import com.chat2pay.app.domain.conversation.LlmProviderType;

public interface LlmProvider {

    LlmProviderType providerType();
}
