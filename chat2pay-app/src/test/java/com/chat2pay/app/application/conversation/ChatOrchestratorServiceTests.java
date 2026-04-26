package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.application.capability.CapabilityRegistry;
import com.chat2pay.app.application.conversation.tool.PaymentToolRegistry;
import com.chat2pay.app.application.conversation.intent.IntentInterpreter;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.persistence.repository.SessionStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatOrchestratorServiceTests {

    private final SessionStore sessions = mock(SessionStore.class);
    private final IntentInterpreter intentInterpreter = mock(IntentInterpreter.class);
    private final LlmRouter llmRouter = mock(LlmRouter.class);
    private final Chat2PayProperties properties = new Chat2PayProperties(
            "COPILOT_PERSONAL",
            "REMOTE_API",
            "HKD",
            new Chat2PayProperties.IntentProperties(true, 350, 0.0),
            null
    );

    @Test
    void obviousUnrelatedRequestReturnsGuidanceWithoutCallingLlm() {
        SessionRecord record = record();
        givenApplyTurn(record);

        ChatTurnResponse response = service().handleUserMessage(
                "profile_1",
                "session_1",
                new SendMessageRequest("what is the weather today?", null, false)
        );

        ContentBlock.InfoCardBlock block = (ContentBlock.InfoCardBlock) response.assistantMessage()
                .contentBlocks().get(0);
        assertThat(block.title()).isEqualTo("Chat2Pay payments only");
        assertThat(block.text()).contains("registered domestic payees");
        verify(llmRouter, never()).currentIfAvailable();
    }

    @Test
    void commonChatCanBeAnsweredByLlm() {
        SessionRecord record = record();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.COPILOT_PERSONAL,
                "gpt-5.4",
                "Hi, I can help you find registered payees or prepare a domestic payment."
        ));
        givenApplyTurn(record);
        when(llmRouter.currentIfAvailable()).thenReturn(Optional.of(provider));

        ChatTurnResponse response = service().handleUserMessage(
                "profile_1",
                "session_1",
                new SendMessageRequest("hi, what can you do?", null, false)
        );

        ChatMessage assistant = response.assistantMessage();
        ContentBlock.TextBlock block = (ContentBlock.TextBlock) assistant.contentBlocks().get(0);
        assertThat(block.text()).contains("registered payees");
        verify(provider).complete(any(LlmCompletionRequest.class));
    }

    private ChatOrchestratorService service() {
        return new ChatOrchestratorService(
                sessions,
                intentInterpreter,
                llmRouter,
                properties,
                new ObjectMapper(),
                mock(SessionTitleSuggester.class),
                new ChatBlockFactory(),
                new ConversationStateMachine(),
                mock(PaymentToolRegistry.class),
                new PaymentPolicyGuard(),
                new CapabilityRegistry(),
                mock(DomesticPaymentJourneyService.class)
        );
    }

    private void givenApplyTurn(SessionRecord record) {
        when(sessions.get("profile_1", "session_1")).thenReturn(record);
        when(sessions.applyTurn(anyString(), anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<SessionRecord, ?> mutator = (Function<SessionRecord, ?>) invocation.getArgument(2);
            return mutator.apply(record);
        });
    }

    private SessionRecord record() {
        SessionRecord record = mock(SessionRecord.class);
        List<ChatMessage> messages = new ArrayList<>();
        when(record.profileId()).thenReturn("profile_1");
        when(record.session()).thenReturn(session());
        when(record.messages()).thenReturn(messages);
        when(record.messageSnapshot()).thenAnswer(invocation -> List.copyOf(messages));
        return record;
    }

    private ChatSessionDetail session() {
        Instant now = Instant.now();
        return new ChatSessionDetail(
                "session_1",
                "Test",
                false,
                ChatSessionStatus.ACTIVE,
                ConversationState.IDLE,
                LlmProviderType.COPILOT_PERSONAL,
                null,
                now,
                now
        );
    }
}
