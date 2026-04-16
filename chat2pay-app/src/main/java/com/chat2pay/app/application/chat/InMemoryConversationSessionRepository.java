package com.chat2pay.app.application.chat;

import com.chat2pay.app.domain.ConversationSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryConversationSessionRepository implements ConversationSessionRepository {

    private final Map<String, Map<String, ConversationSession>> sessionsByProfileId = new ConcurrentHashMap<>();

    @Override
    public List<ConversationSession> findAllByProfileId(String profileId) {
        return sessionsByProfileId.getOrDefault(profileId, Map.of()).values().stream()
                .sorted(Comparator.comparing(ConversationSession::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<ConversationSession> findByProfileIdAndId(String profileId, String sessionId) {
        return Optional.ofNullable(sessionsByProfileId.getOrDefault(profileId, Map.of()).get(sessionId));
    }

    @Override
    public ConversationSession save(ConversationSession session) {
        sessionsByProfileId
                .computeIfAbsent(session.getProfileId(), ignored -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        return session;
    }
}
