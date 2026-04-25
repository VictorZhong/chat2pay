package com.chat2pay.app.persistence.entity;

import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "ctp_chat_message")
@Getter
@Setter
public class ChatMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private MessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private MessageKind kind;

    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_blocks_json", columnDefinition = "jsonb")
    private List<ContentBlock> contentBlocks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

}
