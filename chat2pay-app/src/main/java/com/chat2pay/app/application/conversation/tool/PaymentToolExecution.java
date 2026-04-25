package com.chat2pay.app.application.conversation.tool;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ContentBlock;

import java.util.List;
import java.util.Map;

public record PaymentToolExecution(
        String toolCallId,
        String toolName,
        Map<String, Object> result,
        ChatMessage terminalMessage,
        List<ContentBlock> renderBlocks
) {}
