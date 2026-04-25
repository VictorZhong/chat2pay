package com.chat2pay.app.api;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionSummary;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.CreateChatSessionRequest;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ChatDtos.UiEventRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.application.conversation.ChatOrchestratorService;
import com.chat2pay.app.application.conversation.ChatStreamService;
import com.chat2pay.app.persistence.repository.SessionStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String PROFILE_HEADER = "X-Profile-Id";

    private final SessionStore sessions;
    private final ChatOrchestratorService orchestrator;
    private final ChatStreamService stream;

    public ChatController(SessionStore sessions,
                          ChatOrchestratorService orchestrator,
                          ChatStreamService stream) {
        this.sessions = sessions;
        this.orchestrator = orchestrator;
        this.stream = stream;
    }

    @PostMapping("/sessions")
    public ChatSessionDetail createSession(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @Valid @RequestBody(required = false) CreateChatSessionRequest request) {
        String title = request != null ? request.title() : null;
        ChatSessionDetail detail = sessions.create(profileId, title);
        SessionRecord record = sessions.get(profileId, detail.sessionId());
        record.messages().add(orchestrator.welcomeMessage(detail.sessionId()));
        return record.session();
    }

    @GetMapping("/sessions")
    public List<ChatSessionSummary> listSessions(@RequestHeader(PROFILE_HEADER) String profileId) {
        return sessions.listByProfile(profileId).stream()
                .map(detail -> {
                    SessionRecord record = sessions.get(profileId, detail.sessionId());
                    String preview = lastMessagePreview(record.messages());
                    return new ChatSessionSummary(
                            detail.sessionId(),
                            detail.title(),
                            detail.status(),
                            detail.state(),
                            detail.llmProvider(),
                            preview,
                            detail.createdAt(),
                            detail.updatedAt()
                    );
                })
                .sorted(Comparator.comparing(ChatSessionSummary::updatedAt).reversed())
                .toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public ChatSessionDetail getSession(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId) {
        return sessions.get(profileId, sessionId).session();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> listMessages(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId) {
        SessionRecord record = sessions.get(profileId, sessionId);
        synchronized (record.messages()) {
            return new ArrayList<>(record.messages());
        }
    }

    @PostMapping(path = "/sessions/{sessionId}/messages",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatTurnResponse sendMessage(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        return orchestrator.handleUserMessage(profileId, sessionId, request);
    }

    @PostMapping(path = "/sessions/{sessionId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        return stream.streamUserMessage(profileId, sessionId, request);
    }

    @PostMapping(path = "/sessions/{sessionId}/events",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatTurnResponse submitEvent(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody UiEventRequest request) {
        return orchestrator.handleUiEvent(profileId, sessionId, request);
    }

    @PostMapping(path = "/sessions/{sessionId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvent(
            @RequestHeader(PROFILE_HEADER) String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody UiEventRequest request) {
        return stream.streamUiEvent(profileId, sessionId, request);
    }

    private static String lastMessagePreview(List<ChatMessage> messages) {
        ChatMessage last;
        synchronized (messages) {
            if (messages.isEmpty()) return null;
            last = messages.get(messages.size() - 1);
        }
        if (last.text() != null && !last.text().isBlank()) {
            return truncate(last.text());
        }
        if (last.contentBlocks() != null && !last.contentBlocks().isEmpty()) {
            ContentBlock first = last.contentBlocks().get(0);
            if (first instanceof ContentBlock.TextBlock t) return truncate(t.text());
            if (first instanceof ContentBlock.InfoCardBlock i) return truncate(i.text());
            if (first instanceof ContentBlock.ErrorCardBlock e) return truncate(e.text());
            if (first instanceof ContentBlock.SummaryCardBlock s) return truncate(s.title());
            if (first instanceof ContentBlock.SelectableListBlock s) return truncate(s.title());
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 140 ? s.substring(0, 140) : s;
    }
}
