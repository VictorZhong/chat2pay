package com.chat2pay.app.application.chat;

import com.chat2pay.app.domain.ConversationSession;
import java.util.List;
import java.util.Optional;

public interface ConversationSessionRepository {

    List<ConversationSession> findAllByProfileId(String profileId);

    Optional<ConversationSession> findByProfileIdAndId(String profileId, String sessionId);

    ConversationSession save(ConversationSession session);
}
