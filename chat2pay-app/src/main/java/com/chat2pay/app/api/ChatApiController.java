package com.chat2pay.app.api;

import com.chat2pay.app.application.chat.ChatApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatApiController {

    private final ChatApplicationService chatApplicationService;

    public ChatApiController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    @GetMapping
    public ApiModels.ChatSessionSummaryPageResponse listSessions(
            @RequestHeader("X-Profile-Id") String profileId) {
        return chatApplicationService.listSessions(profileId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.ChatSessionCreateResponse createSession(
            @RequestHeader("X-Profile-Id") String profileId,
            @RequestBody(required = false) ApiModels.CreateChatSessionRequest request) {
        return chatApplicationService.createSession(profileId, request);
    }

    @GetMapping("/{sessionId}")
    public ApiModels.ChatSessionDetailResponse getSession(
            @RequestHeader("X-Profile-Id") String profileId,
            @PathVariable String sessionId) {
        return chatApplicationService.getSession(profileId, sessionId);
    }

    @GetMapping("/{sessionId}/messages")
    public ApiModels.ChatMessagePageResponse listMessages(
            @RequestHeader("X-Profile-Id") String profileId,
            @PathVariable String sessionId) {
        return chatApplicationService.listMessages(profileId, sessionId);
    }

    @PostMapping("/{sessionId}/messages")
    public ApiModels.ChatTurnResponse sendMessage(
            @RequestHeader("X-Profile-Id") String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody ApiModels.SendMessageRequest request) {
        return chatApplicationService.sendMessage(profileId, sessionId, request);
    }

    @PostMapping("/{sessionId}/events")
    public ApiModels.ChatTurnResponse submitEvent(
            @RequestHeader("X-Profile-Id") String profileId,
            @PathVariable String sessionId,
            @Valid @RequestBody ApiModels.UiEventRequest request) {
        return chatApplicationService.submitEvent(profileId, sessionId, request);
    }
}
