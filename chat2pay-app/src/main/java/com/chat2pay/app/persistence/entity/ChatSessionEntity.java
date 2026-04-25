package com.chat2pay.app.persistence.entity;

import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ctp_chat_session")
@Getter
@Setter
public class ChatSessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "profile_id", length = 64, nullable = false)
    private String profileId;

    @Column(length = 160, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private ChatSessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 48, nullable = false)
    private ConversationState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_provider", length = 32)
    private LlmProviderType llmProvider;

    @Column(name = "active_draft_id", length = 64)
    private String activeDraftId;

    @Column(name = "last_message_preview", length = 160)
    private String lastMessagePreview;

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;
}
