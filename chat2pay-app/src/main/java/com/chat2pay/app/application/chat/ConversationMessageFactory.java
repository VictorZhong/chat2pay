package com.chat2pay.app.application.chat;

import com.chat2pay.app.api.ApiModels.ContentBlock;
import com.chat2pay.app.common.UlidFactory;
import com.chat2pay.app.domain.ConversationMessage;
import com.chat2pay.app.domain.MessageRole;
import com.chat2pay.app.domain.MessageType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConversationMessageFactory {

    private final UlidFactory ulidFactory;

    public ConversationMessageFactory(UlidFactory ulidFactory) {
        this.ulidFactory = ulidFactory;
    }

    public ConversationMessage userTextMessage(String sessionId, int sequenceNo, String text) {
        return new ConversationMessage(
                ulidFactory.nextUlid(),
                sessionId,
                sequenceNo,
                MessageRole.USER,
                MessageType.TEXT,
                text,
                List.of(),
                Instant.now());
    }

    public ConversationMessage userEventMessage(String sessionId, int sequenceNo, String text) {
        return new ConversationMessage(
                ulidFactory.nextUlid(),
                sessionId,
                sequenceNo,
                MessageRole.USER,
                MessageType.UI_EVENT,
                text,
                List.of(),
                Instant.now());
    }

    public ConversationMessage assistantMessage(
            String sessionId,
            int sequenceNo,
            String text,
            List<ContentBlock> blocks) {
        return new ConversationMessage(
                ulidFactory.nextUlid(),
                sessionId,
                sequenceNo,
                MessageRole.ASSISTANT,
                inferMessageType(blocks),
                text,
                blocks,
                Instant.now());
    }

    private MessageType inferMessageType(List<ContentBlock> blocks) {
        if (blocks.stream().anyMatch(block -> "SIMPLE_FORM".equals(block.type()))) {
            return MessageType.FORM;
        }
        if (blocks.stream().anyMatch(block -> "SELECTABLE_LIST".equals(block.type()))) {
            return MessageType.LIST;
        }
        if (blocks.stream().anyMatch(block -> !"TEXT".equals(block.type()))) {
            return MessageType.CARD;
        }
        return MessageType.TEXT;
    }
}
