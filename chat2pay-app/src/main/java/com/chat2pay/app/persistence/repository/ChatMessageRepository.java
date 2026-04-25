package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    List<ChatMessageEntity> findBySessionIdOrderBySequenceNoAsc(String sessionId);
}
