package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    List<ChatMessageEntity> findBySessionIdOrderBySequenceNoAsc(String sessionId);

    @Modifying
    @Transactional
    @Query("delete from ChatMessageEntity m where m.sessionId = :sessionId")
    void deleteBySessionId(String sessionId);
}
