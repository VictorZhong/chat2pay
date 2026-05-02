package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;

public interface LlmClient {

    LlmProviderType providerType();

    LlmCompletion complete(LlmRequest request, String model);
}
