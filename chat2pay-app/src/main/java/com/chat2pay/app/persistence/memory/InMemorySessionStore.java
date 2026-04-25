package com.chat2pay.app.persistence.memory;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemorySessionStore {

    public static class SessionRecord {
        private ChatSessionDetail session;
        private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());

        SessionRecord(ChatSessionDetail session) {
            this.session = session;
        }

        public ChatSessionDetail session() { return session; }
        public void setSession(ChatSessionDetail updated) { this.session = updated; }
        public List<ChatMessage> messages() { return messages; }
    }

    private final Map<String, Map<String, SessionRecord>> byProfile = new ConcurrentHashMap<>();

    public ChatSessionDetail create(String profileId, String title) {
        String id = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        ChatSessionDetail detail = new ChatSessionDetail(
                id,
                title == null || title.isBlank() ? "New conversation" : title,
                ChatSessionStatus.ACTIVE,
                ConversationState.IDLE,
                LlmProviderType.COPILOT_PERSONAL,
                null,
                now,
                now
        );
        byProfile.computeIfAbsent(profileId, k -> new ConcurrentHashMap<>())
                .put(id, new SessionRecord(detail));
        return detail;
    }

    public SessionRecord get(String profileId, String sessionId) {
        Map<String, SessionRecord> map = byProfile.get(profileId);
        SessionRecord record = map == null ? null : map.get(sessionId);
        if (record == null) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        return record;
    }

    public List<ChatSessionDetail> listByProfile(String profileId) {
        Map<String, SessionRecord> map = byProfile.get(profileId);
        if (map == null) return List.of();
        return map.values().stream()
                .map(SessionRecord::session)
                .sorted(Comparator.comparing(ChatSessionDetail::updatedAt).reversed())
                .toList();
    }
}
