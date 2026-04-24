package com.chat2pay.app.integration.llm.remote;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class RemoteApiLlmProvider implements LlmProvider {

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.REMOTE_API;
    }
}
