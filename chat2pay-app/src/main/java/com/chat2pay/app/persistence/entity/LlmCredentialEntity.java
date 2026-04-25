package com.chat2pay.app.persistence.entity;

import com.chat2pay.app.domain.conversation.LlmProviderType;
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
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "ctp_llm_credential")
@Getter
@Setter
public class LlmCredentialEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private LlmProviderType provider;

    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey;

    @Column(name = "session_token", columnDefinition = "text")
    private String sessionToken;

    @Column(name = "session_token_expires_at")
    private Instant sessionTokenExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
