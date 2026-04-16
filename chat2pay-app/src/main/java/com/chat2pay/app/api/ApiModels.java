package com.chat2pay.app.api;

import com.chat2pay.app.domain.ChatSessionStatus;
import com.chat2pay.app.domain.JourneyType;
import com.chat2pay.app.domain.MessageRole;
import com.chat2pay.app.domain.MessageType;
import com.chat2pay.app.domain.ProfileStatus;
import com.chat2pay.app.domain.TransferStatus;
import com.chat2pay.app.domain.UiEventType;
import com.chat2pay.app.domain.WorkflowState;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiModels {

    private ApiModels() {
    }

    public record ProfileSummaryResponse(
            String id,
            String code,
            String username,
            String displayName,
            String avatarUrl,
            String mockCustomerId,
            String locale,
            ProfileStatus status,
            List<JourneyType> supportedJourneyTypes) {
    }

    public record CurrentUserContextResponse(
            String profileId,
            String username,
            String displayName,
            String avatarUrl,
            String locale,
            String loginMode,
            List<JourneyType> supportedJourneyTypes) {
    }

    public record ProfileLoginRequest(
            @NotBlank String profileId,
            @NotBlank String password) {
    }

    public record CreateChatSessionRequest(String title) {
    }

    public record ChatSessionCreateResponse(
            ChatSessionDetailResponse session,
            List<ChatMessageResponse> assistantMessages) {
    }

    public record ChatSessionSummaryResponse(
            String sessionId,
            String title,
            ChatSessionStatus status,
            WorkflowState workflowState,
            JourneyType journeyType,
            String lastAssistantText,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ChatSessionSummaryPageResponse(
            List<ChatSessionSummaryResponse> items,
            int page,
            int pageSize,
            int total) {
    }

    public record ChatSessionDetailResponse(
            String sessionId,
            String title,
            ChatSessionStatus status,
            WorkflowState workflowState,
            JourneyType journeyType,
            Instant createdAt,
            Instant updatedAt,
            TransactionDraftResponse activeDraft) {
    }

    public record ChatMessageResponse(
            String messageId,
            String sessionId,
            MessageRole role,
            MessageType messageType,
            String text,
            List<ContentBlock> contentBlocks,
            Instant createdAt) {
    }

    public record ChatMessagePageResponse(
            List<ChatMessageResponse> items,
            int page,
            int pageSize,
            int total) {
    }

    public record SendMessageRequest(
            @NotBlank String messageText,
            String clientMessageId) {
    }

    public record UiEventRequest(
            @NotNull UiEventType eventType,
            @NotBlank String sourceMessageId,
            @NotBlank String sourceBlockId,
            List<String> selectedItemIds,
            Map<String, String> formValues,
            String clientEventId) {
    }

    public record ChatTurnResponse(
            ChatSessionDetailResponse session,
            ChatMessageResponse userEchoMessage,
            List<ChatMessageResponse> assistantMessages,
            TransactionDraftResponse draftSummary,
            WorkflowState workflowState,
            List<SuggestedActionResponse> suggestedActions,
            Instant serverTimestamp) {
    }

    public record SuggestedActionResponse(
            String actionType,
            String label,
            String value) {
    }

    public record TransactionDraftResponse(
            String draftId,
            String sessionId,
            JourneyType journeyType,
            TransferStatus status,
            WorkflowState workflowState,
            String sourceAccountId,
            String sourceAccountDisplay,
            String payeeNameInput,
            String payeeIdIndex,
            String payeeType,
            String payeeDisplay,
            BigDecimal amount,
            String currency,
            String note,
            Map<String, Object> reviewSummary,
            Map<String, Object> downstreamReferences,
            String transferReference,
            Map<String, Object> additionalContext,
            Instant lastUpdatedAt) {
    }

    public record DisplayField(String label, String value) {
    }

    public record SelectableItem(
            String itemId,
            String label,
            String description,
            String value,
            Map<String, Object> metadata) {
    }

    public record FormField(
            String fieldId,
            String label,
            String fieldType,
            Boolean required,
            String placeholder,
            List<SelectableItem> options) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = TextBlock.class, name = "TEXT"),
            @JsonSubTypes.Type(value = SummaryCardBlock.class, name = "SUMMARY_CARD"),
            @JsonSubTypes.Type(value = SelectableListBlock.class, name = "SELECTABLE_LIST"),
            @JsonSubTypes.Type(value = SimpleFormBlock.class, name = "SIMPLE_FORM"),
            @JsonSubTypes.Type(value = ErrorCardBlock.class, name = "ERROR_CARD"),
            @JsonSubTypes.Type(value = InfoCardBlock.class, name = "INFO_CARD")
    })
    public sealed interface ContentBlock permits
            TextBlock,
            SummaryCardBlock,
            SelectableListBlock,
            SimpleFormBlock,
            ErrorCardBlock,
            InfoCardBlock {

        String blockId();

        String type();

        default String displayText() {
            return null;
        }
    }

    public record TextBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return text;
        }
    }

    public record SummaryCardBlock(
            String blockId,
            String type,
            String title,
            List<DisplayField> fields,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return title;
        }
    }

    public record SelectableListBlock(
            String blockId,
            String type,
            String title,
            String selectionMode,
            List<SelectableItem> items,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return title;
        }
    }

    public record SimpleFormBlock(
            String blockId,
            String type,
            String title,
            List<FormField> fields,
            String submitLabel,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return title;
        }
    }

    public record ErrorCardBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return text;
        }
    }

    public record InfoCardBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata) implements ContentBlock {

        @Override
        public String displayText() {
            return text;
        }
    }

    public record ErrorResponse(
            String code,
            String message,
            List<String> details,
            Instant timestamp) {
    }
}
