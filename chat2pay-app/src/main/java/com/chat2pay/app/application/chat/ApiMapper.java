package com.chat2pay.app.application.chat;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.domain.ConversationMessage;
import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApiMapper {

    public ApiModels.ProfileSummaryResponse toProfileSummary(Profile profile) {
        return new ApiModels.ProfileSummaryResponse(
                profile.id(),
                profile.code(),
                profile.username(),
                profile.displayName(),
                profile.avatarUrl(),
                profile.mockCustomerId(),
                profile.locale(),
                profile.status(),
                profile.supportedJourneyTypes());
    }

    public ApiModels.CurrentUserContextResponse toCurrentUser(Profile profile) {
        return new ApiModels.CurrentUserContextResponse(
                profile.id(),
                profile.username(),
                profile.displayName(),
                profile.avatarUrl(),
                profile.locale(),
                "PROFILE_SELECTION",
                profile.supportedJourneyTypes());
    }

    public ApiModels.ChatSessionSummaryPageResponse toSessionSummaryPage(List<ConversationSession> sessions) {
        List<ApiModels.ChatSessionSummaryResponse> items = sessions.stream()
                .map(this::toSessionSummary)
                .toList();
        return new ApiModels.ChatSessionSummaryPageResponse(items, 1, items.size(), items.size());
    }

    public ApiModels.ChatSessionSummaryResponse toSessionSummary(ConversationSession session) {
        return new ApiModels.ChatSessionSummaryResponse(
                session.getId(),
                session.getTitle(),
                session.getStatus(),
                session.getWorkflowState(),
                session.getJourneyType(),
                session.lastAssistantText(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }

    public ApiModels.ChatSessionDetailResponse toSessionDetail(ConversationSession session) {
        return new ApiModels.ChatSessionDetailResponse(
                session.getId(),
                session.getTitle(),
                session.getStatus(),
                session.getWorkflowState(),
                session.getJourneyType(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                toDraft(session.getActiveDraft()));
    }

    public ApiModels.ChatMessagePageResponse toMessagePage(List<ConversationMessage> messages) {
        List<ApiModels.ChatMessageResponse> items = messages.stream()
                .map(this::toMessage)
                .toList();
        return new ApiModels.ChatMessagePageResponse(items, 1, items.size(), items.size());
    }

    public ApiModels.ChatMessageResponse toMessage(ConversationMessage message) {
        return new ApiModels.ChatMessageResponse(
                message.id(),
                message.sessionId(),
                message.role(),
                message.messageType(),
                message.text(),
                message.contentBlocks(),
                message.createdAt());
    }

    public ApiModels.TransactionDraftResponse toDraft(PaymentDraft draft) {
        if (draft == null) {
            return null;
        }

        return new ApiModels.TransactionDraftResponse(
                draft.getId(),
                draft.getSessionId(),
                draft.getJourneyType(),
                draft.getStatus(),
                draft.getWorkflowState(),
                draft.getSourceAccountId(),
                draft.getSourceAccountDisplay(),
                draft.getPayeeNameInput(),
                draft.getPayeeIdIndex(),
                draft.getPayeeType(),
                draft.getPayeeDisplay(),
                draft.getAmount(),
                draft.getCurrency(),
                draft.getNote(),
                nullableMap(draft.getReviewSummary()),
                nullableMap(draft.getDownstreamReferences()),
                draft.getTransferReference(),
                nullableMap(draft.getAdditionalContext()),
                draft.getLastUpdatedAt());
    }

    public ApiModels.ChatTurnResponse toTurnResponse(
            ConversationSession session,
            ConversationMessage userMessage,
            List<ConversationMessage> assistantMessages,
            List<ApiModels.SuggestedActionResponse> suggestedActions) {
        return new ApiModels.ChatTurnResponse(
                toSessionDetail(session),
                toMessage(userMessage),
                assistantMessages.stream().map(this::toMessage).toList(),
                toDraft(session.getActiveDraft()),
                session.getWorkflowState(),
                suggestedActions,
                Instant.now());
    }

    private Map<String, Object> nullableMap(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null : Map.copyOf(value);
    }
}
