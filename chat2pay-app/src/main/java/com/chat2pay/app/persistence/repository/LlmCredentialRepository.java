package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.persistence.entity.LlmCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCredentialRepository extends JpaRepository<LlmCredentialEntity, LlmProviderType> {
}
