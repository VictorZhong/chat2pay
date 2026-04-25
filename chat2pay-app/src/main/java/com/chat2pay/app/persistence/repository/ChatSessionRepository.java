package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    Optional<ChatSessionEntity> findByIdAndProfileId(String id, String profileId);

    List<ChatSessionEntity> findByProfileIdOrderByUpdatedAtDesc(String profileId);
}
