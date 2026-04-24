package com.chat2pay.app.integration.llm.copilot;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class CopilotPersonalLlmProvider implements LlmProvider {

    @Override
    public LlmProviderType providerType() {
        return LlmProviderType.COPILOT_PERSONAL;
    }
}
