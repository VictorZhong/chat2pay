package com.chat2pay.app.api.dto;

import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.domain.conversation.UiEventType;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ChatDtos {
    private ChatDtos() {}

    public record CreateChatSessionRequest(@Size(max = 120) String title) {}

    public record RenameChatSessionRequest(@NotBlank @Size(max = 120) String title) {}

    public record ChatSessionSummary(
            String sessionId,
            String title,
            boolean titleLocked,
            ChatSessionStatus status,
            ConversationState state,
            LlmProviderType llmProvider,
            String lastMessagePreview,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ChatSessionDetail(
            String sessionId,
            String title,
            boolean titleLocked,
            ChatSessionStatus status,
            ConversationState state,
            LlmProviderType llmProvider,
            PaymentDraft activeDraft,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ChatMessage(
            String messageId,
            String sessionId,
            MessageRole role,
            MessageKind kind,
            String text,
            List<ContentBlock> contentBlocks,
            Map<String, Object> metadata,
            Instant createdAt
    ) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 4000) String messageText,
            String clientMessageId,
            Boolean stream
    ) {}

    public record UiEventRequest(
            UiEventType eventType,
            @NotBlank String sourceMessageId,
            @NotBlank String sourceBlockId,
            String selectedItemId,
            String actionValue,
            Map<String, String> formValues,
            String clientEventId,
            Boolean stream
    ) {}

    public record ChatTurnResponse(
            ChatSessionDetail session,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            PaymentDraft activeDraft,
            Instant serverTimestamp
    ) {}

    public record PayeeSummary(
            String payeeId,
            String name,
            String payeeType,
            String bankCode,
            String bankName,
            String accountNumber,
            String displayLabel
    ) {}

    public record ErrorSummary(String code, String message) {}

    public record PaymentDraft(
            String draftId,
            String sessionId,
            PaymentType paymentType,
            PaymentDraftStatus status,
            String payeeQueryText,
            PayeeSummary selectedPayee,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal amount,
            String currency,
            LocalDate paymentDate,
            String downstreamReference,
            ErrorSummary lastError,
            Map<String, Object> context,
            Instant lastUpdatedAt
    ) {}

    public record ErrorResponse(
            String code,
            String message,
            List<String> details,
            Instant timestamp
    ) {}
}
