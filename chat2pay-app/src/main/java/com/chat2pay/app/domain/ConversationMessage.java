package com.chat2pay.app.domain;

import com.chat2pay.app.api.ApiModels.ContentBlock;
import java.time.Instant;
import java.util.List;

public record ConversationMessage(
        String id,
        String sessionId,
        int sequenceNo,
        MessageRole role,
        MessageType messageType,
        String text,
        List<ContentBlock> contentBlocks,
        Instant createdAt) {
}
