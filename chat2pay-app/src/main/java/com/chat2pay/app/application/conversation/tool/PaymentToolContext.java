package com.chat2pay.app.application.conversation.tool;

import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;

import java.util.Map;

public record PaymentToolContext(
        SessionRecord record,
        ToolCall toolCall,
        Map<String, Object> args,
        String latestUserText,
        PaymentToolActions actions
) {}
